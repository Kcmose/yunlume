#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# 仅外部命令使用可执行stub。生产事务函数、OCI解析和资产JSON解析均实际执行。
mkdir -p "$work/bin" "$work/oci/blobs/sha256"
printf '{"schemaVersion":2,"manifests":[]}' > "$work/root.json"
root_hex="$(sha256sum "$work/root.json")"; root_hex="${root_hex%% *}"
cp "$work/root.json" "$work/oci/blobs/sha256/$root_hex"
printf '{"manifests":[{"digest":"sha256:%s","size":%s}]}' \
  "$root_hex" "$(stat -c '%s' "$work/root.json")" > "$work/oci/index.json"
tar -C "$work/oci" -cf "$work/candidate.oci.tar" .
printf payload > "$work/asset.bin"

cat > "$work/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == "buildx imagetools inspect --format {{.Manifest.Digest}} image:candidate" ]] || exit 97
printf 'inspect\n' >> "$TRACE"
case "$FAULT" in
  registry-forbidden) printf '403 Forbidden: credential token not found\n' >&2; exit 23 ;;
  registry-network) printf 'dial tcp: connection timed out\n' >&2; exit 24 ;;
  registry-helper-missing) printf 'credential helper not found\n' >&2; exit 25 ;;
esac
if [[ -s "$REGISTRY" ]]; then
  if [[ -e "$COPIED" ]]; then
    case "$FAULT" in
      readback-forbidden) printf '403 Forbidden\n' >&2; exit 26 ;;
      readback-network) printf 'connection reset by peer\n' >&2; exit 27 ;;
    esac
  fi
  cat "$REGISTRY"
else
  printf 'manifest unknown\n' >&2
  exit 1
fi
MOCK

cat > "$work/bin/skopeo" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 5 && "$1" == copy && "$2" == --all && "$3" == --preserve-digests \
  && "$4" == "oci-archive:$ARCHIVE" && "$5" == docker://image:candidate ]] || exit 97
printf 'copy\n' >> "$TRACE"
[[ "$FAULT" != copy-network ]] || { printf 'registry connection failed\n' >&2; exit 31; }
printf '%s\n' "$EXPECTED" > "$REGISTRY"
: > "$COPIED"
MOCK

cat > "$work/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1 $2" == 'attestation verify' ]]; then
  printf 'attestation\n' >> "$TRACE"
  [[ "$3" == "oci://image@$EXPECTED" ]] || exit 97
  case "$FAULT" in
    signature-mismatch) printf 'signature mismatch\n' >&2; exit 41 ;;
    attestation-missing) printf 'no attestations found\n' >&2; exit 42 ;;
    attestation-forbidden) printf 'HTTP 403 Forbidden\n' >&2; exit 43 ;;
    attestation-network) printf 'network request failed\n' >&2; exit 7 ;;
  esac
  exit 0
fi
[[ "$1" == api ]] || exit 97
if [[ "$*" == *'--method DELETE'* || "$*" == *'--method POST'* || "$*" == *'--method PATCH'* ]]; then
  printf 'mutation\n' >> "$TRACE"
  exit 0
fi
if [[ "$*" == *'repos/repo/name/releases/77/assets --paginate --slurp'* ]]; then
  printf 'asset-list\n' >> "$TRACE"
  # 故障也会输出合法JSON；只以JSON能解析来判断成功会绕过API失败。
  printf '[]\n'
  case "$FAULT" in
    asset-forbidden) printf 'HTTP 403 Forbidden\n' >&2; exit 43 ;;
    asset-network) printf 'network request failed\n' >&2; exit 7 ;;
  esac
  exit 0
fi
if [[ "$*" == *'repos/repo/name/releases/77 --jq'* ]]; then
  printf '77\ttrue\tfalse\tv1.2.3\t%s\tanchor\tfalse\n' "$GITHUB_SHA"
  exit 0
fi
if [[ "$*" == *"repos/repo/name/commits/$GITHUB_SHA"* ]]; then printf '%s\n' "$GITHUB_SHA"; exit 0; fi
printf 'unexpected mocked gh call: %s\n' "$*" >&2
exit 97
MOCK
chmod 0755 "$work/bin/docker" "$work/bin/skopeo" "$work/bin/gh"

cat > "$work/driver.sh" <<'DRIVER'
#!/usr/bin/env bash
set -u
# 同时覆盖设置和关闭errexit/pipefail的宿主，生产函数必须自行维持失败契约。
if [[ "$OPTIONS" == on ]]; then set -eo pipefail; else set +e; set +o pipefail; fi
unset -f docker gh skopeo
source "$HELPER"
invoke() {
  case "$TARGET" in
    candidate) publish_candidate_transaction image candidate "$ARCHIVE" "$EXPECTED" ;;
    list) list_release_asset_ids repo/name 77 asset.bin ;;
    upload) upload_release_asset_by_id repo/name 77 "$ASSET" v1.2.3 "$GITHUB_SHA" anchor ;;
  esac
}
case "$CONTEXT" in
  bare) invoke ;;
  conditional) if invoke; then exit 0; else exit "$?"; fi ;;
  substitution)
    result="$(invoke)"; status=$?
    (( status == 0 )) || exit "$status"
    printf '%s' "$result"
    ;;
esac
DRIVER

PATH="$work/bin:$PATH"
HELPER="$SCRIPT_DIR/lib/release-transaction.sh"
TRACE="$work/trace" REGISTRY="$work/registry" COPIED="$work/copied"
ARCHIVE="$work/candidate.oci.tar" ASSET="$work/asset.bin" EXPECTED="sha256:$root_hex"
REPOSITORY=repo/name RELEASE_TAG=v1.2.3 GITHUB_SHA="$(printf a%.0s {1..40})"
export PATH HELPER TRACE REGISTRY COPIED ARCHIVE ASSET EXPECTED REPOSITORY RELEASE_TAG GITHUB_SHA
checks=0

reset_case() {
  : > "$TRACE"
  : > "$REGISTRY"
  rm -f -- "$COPIED"
  [[ "$1" != existing ]] || printf '%s\n' "$EXPECTED" > "$REGISTRY"
}

for OPTIONS in on off; do
  for CONTEXT in bare conditional substitution; do
    export OPTIONS CONTEXT
    TARGET=candidate; export TARGET
    for initial in existing absent; do
      FAULT=none; export FAULT
      reset_case "$initial"
      if output="$(bash "$work/driver.sh" 2> "$work/error")"; then status=0; else status=$?; fi
      [[ "$status" -eq 0 && "$output" == "$EXPECTED" ]] || fail "happy $initial/$CONTEXT/$OPTIONS rejected: $(<"$work/error")"
      [[ "$(grep -c '^attestation$' "$TRACE")" -eq 1 ]] || fail 'happy candidate did not verify attestation'
      checks=$((checks + 1))
      for FAULT in signature-mismatch attestation-missing attestation-forbidden attestation-network; do
        export FAULT
        reset_case "$initial"
        if output="$(bash "$work/driver.sh" 2> "$work/error")"; then status=0; else status=$?; fi
        [[ "$status" -ne 0 && -z "$output" ]] || fail "$FAULT $initial/$CONTEXT/$OPTIONS produced a successful digest"
        [[ "$(grep -c '^attestation$' "$TRACE")" -eq 1 ]] || fail "$FAULT failed before intended verifier"
        checks=$((checks + 1))
      done
    done
    for FAULT in registry-forbidden registry-network registry-helper-missing copy-network readback-forbidden readback-network; do
      export FAULT
      reset_case absent
      if output="$(bash "$work/driver.sh" 2> "$work/error")"; then status=0; else status=$?; fi
      [[ "$status" -ne 0 && -z "$output" ]] || fail "$FAULT $CONTEXT/$OPTIONS was accepted"
      if grep -q '^attestation$' "$TRACE"; then fail "$FAULT continued into signature verification"; fi
      if [[ "$FAULT" == registry-* ]] && grep -q '^copy$' "$TRACE"; then
        fail "$FAULT was mistaken for an absent manifest and attempted publication"
      fi
      checks=$((checks + 1))
    done
    for TARGET in list upload; do
      export TARGET
      for FAULT in asset-forbidden asset-network; do
        export FAULT
        reset_case absent
        if output="$(bash "$work/driver.sh" 2> "$work/error")"; then status=0; else status=$?; fi
        [[ "$status" -ne 0 && -z "$output" ]] || fail "$TARGET $FAULT $CONTEXT/$OPTIONS accepted a failed API response"
        if grep -q '^mutation$' "$TRACE"; then fail "$TARGET mutated Release assets after failed API listing"; fi
        checks=$((checks + 1))
      done
    done
  done
done
printf 'Release failure propagation tests passed (%s cases; executable CLI stubs, no network).\n' "$checks"
