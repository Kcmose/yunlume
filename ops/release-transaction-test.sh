#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=ops/lib/release-transaction.sh
source "${SCRIPT_DIR}/lib/release-transaction.sh"
AWK_BIN="${AWK_BIN:-awk}"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [[ "$1" == "$2" ]] || fail "expected [$2], got [$1]"; }

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

# An OCI archive's byte hash is not its root descriptor digest. The helper must
# read index.json, validate the selected root blob, and return that OCI digest.
mkdir -p "$work/oci/blobs/sha256"
printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}' > "$work/root.json"
root_hex="$(sha256sum "$work/root.json" | "$AWK_BIN" '{print $1}')"
cp "$work/root.json" "$work/oci/blobs/sha256/$root_hex"
printf '{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:%s","size":%s}]}' \
  "$root_hex" "$(stat -c '%s' "$work/root.json")" > "$work/oci/index.json"
printf '{"imageLayoutVersion":"1.0.0"}' > "$work/oci/oci-layout"
tar -C "$work/oci" -cf "$work/candidate.oci.tar" .
archive_hex="$(sha256sum "$work/candidate.oci.tar" | "$AWK_BIN" '{print $1}')"
[[ "$archive_hex" != "$root_hex" ]] || fail 'fixture did not distinguish archive SHA from OCI root digest'
assert_eq "$(oci_archive_root_digest "$work/candidate.oci.tar")" "sha256:$root_hex"
assert_eq "$(oci_archive_file_sha256 "$work/candidate.oci.tar")" "$archive_hex"
cat > "$work/candidate-commitment.json" <<JSON
{"archiveSha256":"$archive_hex","archiveSize":$(stat -c '%s' "$work/candidate.oci.tar"),"component":"backend","digest":"sha256:$root_hex","schemaVersion":2,"sourceSha":"$(printf a%.0s {1..40})"}
JSON
verify_candidate_archive_commitment "$work/candidate.oci.tar" "$work/candidate-commitment.json" backend "$(printf a%.0s {1..40})"
printf 'x' >> "$work/candidate-commitment.json"
if verify_candidate_archive_commitment "$work/candidate.oci.tar" "$work/candidate-commitment.json" backend "$(printf a%.0s {1..40})" >/dev/null 2>&1; then
  fail 'malformed candidate commitment was accepted'
fi
printf 'tamper' >> "$work/oci/blobs/sha256/$root_hex"
tar -C "$work/oci" -cf "$work/tampered.oci.tar" .
if oci_archive_root_digest "$work/tampered.oci.tar" >/dev/null 2>&1; then
  fail 'tampered OCI root blob was accepted'
fi

# Candidate publication has one externally visible commit point: the registry
# manifest PUT behind skopeo's final named reference. Before that point an
# expired Actions artifact is irrelevant; after it, the known candidate ref is
# the durable digest index. Exercise every interruption boundary.
registry_digest=''
publish_calls=0
verify_calls=0
candidate_registry_digest() {
  [[ -n "$registry_digest" ]] || return 44
  printf '%s' "$registry_digest"
}
candidate_copy_to_registry() {
  publish_calls=$((publish_calls + 1))
  case "${INTERRUPT_AT:-}" in
    before-final-manifest) return 71 ;;
    after-final-manifest) registry_digest="$4"; return 72 ;;
  esac
  registry_digest="$4"
}
candidate_verify_attestation() { verify_calls=$((verify_calls + 1)); }
export -f candidate_registry_digest candidate_copy_to_registry candidate_verify_attestation

expected="sha256:$root_hex"
for phase in before-build after-build after-root-validation after-attestation before-final-manifest after-final-manifest after-readback; do
  registry_digest=''; publish_calls=0; verify_calls=0; INTERRUPT_AT=''
  case "$phase" in
    before-build|after-build|after-root-validation|after-attestation)
      # Interruption precedes the registry helper. Even if an attestation was
      # already persisted, no named candidate exists and its digest need not be
      # discovered to retry safely after artifact expiry.
      assert_eq "$registry_digest" ''
      ;;
    before-final-manifest|after-final-manifest)
      INTERRUPT_AT="$phase"
      if publish_candidate_transaction image candidate "$work/candidate.oci.tar" "$expected"; then
        fail "interrupted candidate publication unexpectedly succeeded at $phase"
      fi
      if [[ "$phase" == before-final-manifest ]]; then
        assert_eq "$registry_digest" ''
      else
        assert_eq "$registry_digest" "$expected"
      fi
      INTERRUPT_AT=''
      ;;
    after-readback)
      publish_candidate_transaction image candidate "$work/candidate.oci.tar" "$expected" >/dev/null
      ;;
  esac
  # Simulate Actions artifact expiry. Recovery either safely commits a missing
  # named reference or discovers the committed digest at that known address.
  publish_candidate_transaction image candidate "$work/candidate.oci.tar" "$expected" >/dev/null
  assert_eq "$registry_digest" "$expected"
  before_retry_publish_calls="$publish_calls"
  publish_candidate_transaction image candidate "$work/candidate.oci.tar" "$expected" >/dev/null
  assert_eq "$publish_calls" "$before_retry_publish_calls"
  [[ "$verify_calls" -ge 2 ]] || fail "candidate attestation not verified for $phase"
done
registry_digest="sha256:$(printf b%.0s {1..64})"
if publish_candidate_transaction image candidate "$work/candidate.oci.tar" "$expected" >/dev/null 2>&1; then
  fail 'moved candidate reference was accepted'
fi

# Exact-ID state checks happen immediately before each destructive/upload/PATCH
# operation. Mocks change the immutable release between reads and prove no API
# mutation occurs on either publication race.
release_reads_file="$work/release-reads"
mutations_file="$work/mutations"
printf 0 > "$release_reads_file"
: > "$mutations_file"
MOCK_STATE=draft
MOCK_MARKER='anchor'
MOCK_TAG=v1.2.3
MOCK_SHA="$(printf a%.0s {1..40})"
gh() {
  if [[ "$*" == *'--method DELETE'* || "$*" == *'--method POST'* || "$*" == *'--method PATCH'* ]]; then
    printf '%s\n' "$*" >> "$mutations_file"; return 0
  fi
  if [[ "$*" == *'/immutable-releases'* ]]; then printf '%s\ttrue\n' "${MOCK_IMMUTABLE_ENABLED:-true}"; return 0; fi
  if [[ "$*" == *'/releases/77/assets'* ]]; then
    if [[ "${MOCK_EXISTING_ASSET:-false}" == true ]]; then
      printf '[{"name":"asset.bin","id":991}]\n'
    else
      printf '[]\n'
    fi
    return 0
  fi
  if [[ "$*" == *'/releases/77'* ]]; then
    local reads
    reads="$(( $(<"$release_reads_file") + 1 ))"
    printf '%s' "$reads" > "$release_reads_file"
    if [[ "${RACE_ON_READ:-0}" -eq "$reads" ]]; then MOCK_STATE=published; fi
    if [[ "$MOCK_STATE" == draft ]]; then
      printf '77\ttrue\tfalse\t%s\t%s\t%s\tfalse\n' "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"
    else
      printf '77\tfalse\tfalse\t%s\t%s\t%s\ttrue\n' "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"
    fi
    return 0
  fi
  if [[ "$*" == *'/commits/'* ]]; then printf '%s\n' "$MOCK_SHA"; return 0; fi
  fail "unexpected gh call: $*"
}
export -f gh
export release_reads_file mutations_file MOCK_STATE MOCK_MARKER MOCK_TAG MOCK_SHA RACE_ON_READ MOCK_EXISTING_ASSET
printf x > "$work/asset.bin"
printf 0 > "$release_reads_file"; : > "$mutations_file"; MOCK_STATE=draft; RACE_ON_READ=1; export MOCK_STATE RACE_ON_READ
if upload_release_asset_by_id repo/name 77 "$work/asset.bin" "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"; then
  fail 'upload race unexpectedly succeeded'
fi
assert_eq "$(wc -l < "$mutations_file")" 0
printf 0 > "$release_reads_file"; : > "$mutations_file"; MOCK_STATE=draft; MOCK_EXISTING_ASSET=true; RACE_ON_READ=1; export MOCK_STATE MOCK_EXISTING_ASSET RACE_ON_READ
if upload_release_asset_by_id repo/name 77 "$work/asset.bin" "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"; then
  fail 'clobber race unexpectedly succeeded'
fi
assert_eq "$(wc -l < "$mutations_file")" 0
MOCK_EXISTING_ASSET=false; export MOCK_EXISTING_ASSET
printf 0 > "$release_reads_file"; : > "$mutations_file"; MOCK_STATE=draft; RACE_ON_READ=1; export MOCK_STATE RACE_ON_READ
if publish_release_by_id repo/name 77 "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"; then
  fail 'publication race unexpectedly succeeded'
fi
assert_eq "$(wc -l < "$mutations_file")" 0

# Happy paths still use immutable release ID endpoints, never tag resolution.
printf 0 > "$release_reads_file"; : > "$mutations_file"; MOCK_STATE=draft; RACE_ON_READ=0; export MOCK_STATE RACE_ON_READ
upload_release_asset_by_id repo/name 77 "$work/asset.bin" "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"
assert_eq "$(wc -l < "$mutations_file")" 1
[[ "$(<"$mutations_file")" == *'--hostname uploads.github.com --method POST repos/repo/name/releases/77/assets?name=asset.bin'* ]] ||
  fail 'asset upload did not use exact release ID/upload host'
printf 0 > "$release_reads_file"; : > "$mutations_file"; MOCK_STATE=draft; export MOCK_STATE
publish_release_by_id repo/name 77 "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"
assert_eq "$(wc -l < "$mutations_file")" 1
[[ "$(<"$mutations_file")" == *'--method PATCH repos/repo/name/releases/77'* ]] ||
  fail 'publication did not PATCH exact release ID'

# One canonical custom predicate cryptographically binds the first invocation,
# workflow/source identity, complete subjects, and exact candidate commitments.
mkdir -p "$work/provenance"
printf one > "$work/provenance/a"
printf two > "$work/provenance/b"
a="$(sha256sum "$work/provenance/a" | "$AWK_BIN" '{print $1}')"
b="$(sha256sum "$work/provenance/b" | "$AWK_BIN" '{print $1}')"
backend_candidate="sha256:$(printf 1%.0s {1..64})"
frontend_candidate="sha256:$(printf 2%.0s {1..64})"
predicate_type='https://yunlume.example/attestations/release-invocation/v1'
python3 - "$work/provenance/bundle.json" "$a" "$b" "$backend_candidate" "$frontend_candidate" <<'PY'
import base64, json, sys
predicate={"schemaVersion":1,"githubRunId":"123","githubRunAttempt":"1",
 "sourceSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sourceRef":"refs/tags/v1.2.3",
 "workflowIdentity":"repo/name/.github/workflows/publish-images.yml",
 "workflowRef":"repo/name/.github/workflows/publish-images.yml@refs/tags/v1.2.3",
 "candidateDigests":{"backend":sys.argv[4],"frontend":sys.argv[5]},
 "subjects":{"a":sys.argv[2],"b":sys.argv[3]}}
statement={"_type":"https://in-toto.io/Statement/v1","subject":[
 {"name":"a","digest":{"sha256":sys.argv[2]}}, {"name":"b","digest":{"sha256":sys.argv[3]}}],
 "predicateType":"https://yunlume.example/attestations/release-invocation/v1","predicate":predicate}
bundle={"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json","dsseEnvelope":{
 "payload":base64.b64encode(json.dumps(statement,separators=(',',':'),sort_keys=True).encode()).decode(),
 "payloadType":"application/vnd.in-toto+json","signatures":[{"sig":"fixture-signature"}]},
 "verificationMaterial":{"fixture":"certificate-chain"}}
open(sys.argv[1],'w').write(json.dumps(bundle,sort_keys=True))
PY
bundle_sha="$(sha256sum "$work/provenance/bundle.json" | "$AWK_BIN" '{print $1}')"
predicate_sha="$(python3 - "$work/provenance/bundle.json" <<'PY'
import base64,hashlib,json,sys
b=json.load(open(sys.argv[1])); s=json.loads(base64.b64decode(b['dsseEnvelope']['payload']))
p=json.dumps(s['predicate'],separators=(',',':'),sort_keys=True).encode(); print(hashlib.sha256(p).hexdigest())
PY
)"
cat > "$work/provenance/provenance.json" <<JSON
{"schemaVersion":3,"canonicalIdentity":"123:1:$predicate_sha:$bundle_sha","attestationBundleSha256":"$bundle_sha","predicateSha256":"$predicate_sha","backendJarSha256":"$(printf d%.0s {1..64})","assets":{"a":{"sha256":"$a","size":3},"b":{"sha256":"$b","size":3}},"manifest":{}}
JSON
canonical_args=("$work/provenance" "$work/provenance/provenance.json" "$work/provenance/bundle.json" \
  123 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa refs/tags/v1.2.3 \
  repo/name/.github/workflows/publish-images.yml \
  repo/name/.github/workflows/publish-images.yml@refs/tags/v1.2.3 \
  "$backend_candidate" "$frontend_candidate")
verify_canonical_provenance "${canonical_args[@]}"
if verify_canonical_provenance "${canonical_args[@]:0:3}" 999 "${canonical_args[@]:4}" >/dev/null 2>&1; then
  fail 'canonical bundle with the wrong signed run was accepted'
fi
if verify_canonical_provenance "${canonical_args[@]:0:4}" 2 "${canonical_args[@]:5}" >/dev/null 2>&1; then
  fail 'canonical bundle with the wrong signed attempt was accepted'
fi
cp "$work/provenance/bundle.json" "$work/provenance/replacement.json"
python3 - "$work/provenance/replacement.json" <<'PY'
import base64,json,sys
p=json.load(open(sys.argv[1])); s=json.loads(base64.b64decode(p['dsseEnvelope']['payload']))
s['predicate']['githubRunAttempt']='2'
p['dsseEnvelope']['payload']=base64.b64encode(json.dumps(s,separators=(',',':'),sort_keys=True).encode()).decode()
open(sys.argv[1],'w').write(json.dumps(p,sort_keys=True))
PY
if verify_canonical_provenance "$work/provenance" "$work/provenance/provenance.json" \
  "$work/provenance/replacement.json" "${canonical_args[@]:3}" >/dev/null 2>&1; then
  fail 'conflicting rerun canonical bundle replacement was accepted'
fi

# The tested JAR is the byte source for both delivery formats.
mkdir -p "$work/host/backend"
printf 'tested-jar-bytes' > "$work/tested.jar"
jar_sha="$(sha256sum "$work/tested.jar" | "$AWK_BIN" '{print $1}')"
cp "$work/tested.jar" "$work/host/backend/yunlume-backend.jar"
tar -C "$work/host" -czf "$work/host.tar.gz" .
verify_host_archive_jar_sha "$work/host.tar.gz" "$jar_sha"
docker() {
  [[ "$*" == *'--entrypoint sha256sum'* && "$*" == *'/app/app.jar'* ]] || fail "unexpected docker call: $*"
  printf '%s  /app/app.jar\n' "$MOCK_IMAGE_JAR_SHA"
}
export -f docker
MOCK_IMAGE_JAR_SHA="$jar_sha"; export MOCK_IMAGE_JAR_SHA
verify_container_jar_sha image@sha256:abc "$jar_sha"
MOCK_IMAGE_JAR_SHA="$(printf c%.0s {1..64})"; export MOCK_IMAGE_JAR_SHA
if verify_container_jar_sha image@sha256:abc "$jar_sha" >/dev/null 2>&1; then
  fail 'backend image with a different JAR was accepted'
fi

# CLI capability failures use an executable, argument-preserving gh boundary.
mkdir -p "$work/mock-bin"
policy_log="$work/policy-argv"
cat > "$work/mock-bin/gh" <<'MOCKGH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" >> "$MOCK_GH_ARGV"
if [[ "$1" == version && "$#" -eq 1 ]]; then
  printf 'gh version %s (fixture)\n' "${MOCK_GH_VERSION:-2.93.0}"
  exit 0
fi

command_name="${1-} ${2-} ${3-}"
case "$command_name" in
  'release verify --help'|'release verify-asset --help'|'attestation verify --help')
    [[ "${MOCK_MISSING_SUBCOMMAND:-}" != "$command_name" ]]
    ;;
  *) printf 'unexpected executable gh call: %s\n' "$*" >&2; exit 97 ;;
esac
MOCKGH
chmod 0755 "$work/mock-bin/gh"
unset -f gh
PATH="$work/mock-bin:$PATH"; export PATH
MOCK_GH_ARGV="$policy_log"; export MOCK_GH_ARGV

# The pinned CLI check rejects both an old version and absent required commands.
MOCK_GH_VERSION=2.92.1; export MOCK_GH_VERSION
if assert_pinned_gh_capabilities >/dev/null 2>&1; then fail 'old gh was accepted'; fi
MOCK_GH_VERSION=2.93.0; MOCK_MISSING_SUBCOMMAND='release verify-asset --help'; export MOCK_GH_VERSION MOCK_MISSING_SUBCOMMAND
if assert_pinned_gh_capabilities >/dev/null 2>&1; then fail 'gh without verify-asset was accepted'; fi
MOCK_MISSING_SUBCOMMAND=''; export MOCK_MISSING_SUBCOMMAND
assert_pinned_gh_capabilities

# Dynamic API selectors never interpolate names into jq and invalid names fail early.
gh() { printf '%s\0' "$@" > "$work/safe-gh-argv"; printf '[]\n'; }
export -f gh
printf payload > "$work/valid-asset_1.bin"
list_release_asset_ids repo/name 77 'valid-asset_1.bin' >/dev/null
python3 - "$work/safe-gh-argv" <<'PY'
import sys
args=open(sys.argv[1],'rb').read().split(b'\0')[:-1]
assert b'--jq' not in args
assert b'valid-asset_1.bin' not in args
PY
for invalid in 'quote"x' 'slash\\x' $'line\nbreak' '../escape' ''; do
  if validate_release_asset_name "$invalid" >/dev/null 2>&1; then
    fail 'invalid release asset name was accepted'
  fi
done

# Candidate recovery is an explicit state machine. The durable OCI bytes precede
# the immutable-once-written commitment, and the registry locator is last.
assert_eq "$(candidate_recovery_action false false false)" build-and-stage
assert_eq "$(candidate_recovery_action false true false)" finish-commitment
assert_eq "$(candidate_recovery_action true true false)" publish-archive
assert_eq "$(candidate_recovery_action true false true)" reuse-locator
assert_eq "$(candidate_recovery_action true true true)" reuse-locator
for state in 'true false false' 'false false true' 'false true true'; do
  read -r has_commitment has_archive has_locator <<<"$state"
  if candidate_recovery_action "$has_commitment" "$has_archive" "$has_locator" >/dev/null 2>&1; then
    fail "unsafe candidate state was accepted: $state"
  fi
done

committed="$expected"
moved="sha256:$(printf e%.0s {1..64})"
[[ "$(select_committed_candidate_digest "$committed" "$committed")" == "$committed" ]]
if select_committed_candidate_digest "$committed" "$moved" >/dev/null 2>&1; then
  fail 'moved same-SHA attested candidate locator was accepted'
fi
[[ "$(recover_candidate_without_archive "$committed" "$committed" /does/not/exist)" == "$committed" ]]
if recover_candidate_without_archive "$committed" '' /does/not/exist >/dev/null 2>&1; then
  fail 'commitment without locator or durable archive was accepted'
fi

# Immutable-once-written uploads never delete or replace existing archive,
# commitment, or owner bytes. Existing identical bytes are reused.
once_mutations="$work/once-mutations"
: > "$once_mutations"
REMOTE_ASSET="$work/remote-asset"
printf same > "$REMOTE_ASSET"
gh() {
  if [[ "$*" == *'/releases/77/assets'* ]]; then printf '[{"name":"once.bin","id":991}]\n'; return; fi
  if [[ "$*" == *'/releases/assets/991'* ]]; then cat "$REMOTE_ASSET"; return; fi
  if [[ "$*" == *'/releases/77'* ]]; then
    printf '77\ttrue\tfalse\t%s\t%s\t%s\tfalse\n' "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"; return
  fi
  if [[ "$*" == *'/commits/'* ]]; then printf '%s\n' "$MOCK_SHA"; return; fi
  if [[ "$*" == *'--method DELETE'* || "$*" == *'--method POST'* ]]; then printf '%s\n' "$*" >> "$once_mutations"; return; fi
  fail "unexpected immutable upload gh call: $*"
}
export -f gh
export REMOTE_ASSET once_mutations
printf same > "$work/once.bin"
ensure_release_asset_once_by_id repo/name 77 "$work/once.bin" "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER"
assert_eq "$(wc -l < "$once_mutations")" 0
printf different > "$work/once.bin"
if ensure_release_asset_once_by_id repo/name 77 "$work/once.bin" "$MOCK_TAG" "$MOCK_SHA" "$MOCK_MARKER" >/dev/null 2>&1; then
  fail 'different immutable asset bytes were replaced'
fi
assert_eq "$(wc -l < "$once_mutations")" 0

# Canonical ownership is absent during reservation and may be taken over until
# finalization. After finalization only the exact signed owner may continue.
assert_eq "$(canonical_ownership_action '' 200 3)" finalize-current
assert_eq "$(canonical_ownership_action '200:3' 200 3)" reuse-finalized
if canonical_ownership_action '100:1' 200 3 >/dev/null 2>&1; then
  fail 'conflicting run was accepted after canonical finalization'
fi

printf 'Release transaction behavioral tests passed.\n'
