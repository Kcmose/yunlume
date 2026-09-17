#!/usr/bin/env bash
# 正式 Release 的附件同步与发布；调用前必须通过不可变发布策略检查。
set -euo pipefail
source ops/lib/release-transaction.sh
source ops/lib/publish-workflow.sh

assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1

recovery_marker="$RELEASE_MARKER"
resolve_target_commitish() {
  local encoded_target
  encoded_target="$(jq -rn --arg value "$1" '$value|@uri')" || return
  gh api "repos/$REPOSITORY/commits/$encoded_target" --jq '.sha'
}
releases_json="$(gh api -H 'X-GitHub-Api-Version: 2026-03-10' --paginate "repos/$REPOSITORY/releases")"
matching_text="$(jq --arg tag "$RELEASE_TAG" -r \
  '.[] | select(.tag_name == $tag) | [.id, .draft, .target_commitish, .prerelease, .immutable] | @tsv' \
  <<<"$releases_json")"
matching_releases=()
[[ -z "$matching_text" ]] || mapfile -t matching_releases <<<"$matching_text"
if (( ${#matching_releases[@]} == 1 )); then
  IFS=$'\t' read -r release_id release_draft release_target release_prerelease release_immutable <<< "${matching_releases[0]}"
  release_body="$(gh api "repos/$REPOSITORY/releases/$release_id" --jq '.body // ""')"
  [[ "$release_draft" == true && "$release_id" == "$RESERVED_RELEASE_ID" && "$release_body" == "$RELEASE_MARKER" ]] || {
    printf 'Release is not the exact recoverable unowned draft transaction.\n' >&2; exit 1;
  }
  recovery_marker="$RELEASE_MARKER"
  resolved_release_target="$(resolve_target_commitish "$release_target")"
  [[ "$resolved_release_target" == "$GITHUB_SHA" && "$release_prerelease" == false ]] || {
    printf 'Release target does not resolve to the workflow commit or state changed after preflight.\n' >&2
    exit 1
  }
else
  printf 'Expected exactly one reserved or published Release for %s.\n' "$RELEASE_TAG" >&2
  exit 1
fi

archive_name="yunlume-host-v${VERSION}.tar.gz"
expected_assets=(
  release/install.sh \
  release/yunlume-compose.yml \
  release/release-manifest.json \
  release/candidate-commitment-backend.json \
  release/candidate-commitment-frontend.json \
  release/candidate-oci-backend.tar \
  release/candidate-oci-frontend.tar \
  release/canonical-owner.json \
  release/backend-jar-producer.json \
  release/release-provenance.json \
  release/release-assets.sigstore.json \
  "release/$archive_name" \
  "release/$archive_name.sha256" \
  release/SHA256SUMS
)

verify_published_release() {
  local release_state published_draft published_prerelease published_tag published_target published_body published_immutable
  local resolved_release_target verify_dir row asset_name asset_bytes asset_id actual_bytes names_text remote_asset_text
  local canonical_run_id canonical_run_attempt canonical_backend_digest canonical_frontend_digest
  local component candidate_digest image immutable_digest tag_object tag_object_type tag_object_sha
  local -a expected_names=() remote_asset_rows=() remote_names=() checksum_names=() expected_checksum_names_sorted=() attestation_args=()
  local -A remote_asset_bytes=() remote_asset_ids=()

  release_state="$(gh api -H 'X-GitHub-Api-Version: 2026-03-10' \
    "repos/$REPOSITORY/releases/$release_id" \
    --jq '[.draft, .prerelease, .tag_name, .target_commitish, (.body // ""), .immutable] | @tsv')" || return
  IFS=$'\t' read -r published_draft published_prerelease published_tag published_target published_body published_immutable <<< "$release_state"
  [[ "$published_draft" == false && "$published_prerelease" == false &&
     "$published_tag" == "$RELEASE_TAG" && "$published_body" == "$recovery_marker" &&
     "$published_immutable" == true ]] || {
    printf 'Release publication read-back did not match the intended immutable release.\n' >&2
    return 1
  }
  resolved_release_target="$(resolve_target_commitish "$published_target")" || return
  [[ "$resolved_release_target" == "$GITHUB_SHA" ]] || {
    printf 'Published Release target does not resolve to workflow commit.\n' >&2
    return 1
  }

  names_text="$(printf '%s\n' "${expected_assets[@]##*/}" | sort)" || return
  mapfile -t expected_names <<< "$names_text"
  remote_asset_text="$(gh api "repos/$REPOSITORY/releases/$release_id/assets" --paginate \
    --jq '.[] | [.name, .size, .id] | @tsv')" || return
  remote_asset_text="$(sort <<< "$remote_asset_text")" || return
  mapfile -t remote_asset_rows <<< "$remote_asset_text"
  for row in "${remote_asset_rows[@]}"; do
    IFS=$'\t' read -r asset_name asset_bytes asset_id <<< "$row"
    [[ "$asset_bytes" =~ ^[1-9][0-9]*$ ]] || {
      printf 'Published asset has invalid size: %s.\n' "$asset_name" >&2
      return 1
    }
    remote_names+=("$asset_name")
    remote_asset_bytes["$asset_name"]="$asset_bytes"
    remote_asset_ids["$asset_name"]="$asset_id"
  done
  [[ "${remote_names[*]}" == "${expected_names[*]}" ]] || {
    printf 'Published Release asset set does not match the immutable contract.\n' >&2
    return 1
  }

  verify_dir="$(mktemp -d)" || return
  for asset_name in "${expected_names[@]}"; do
    gh api "repos/$REPOSITORY/releases/assets/${remote_asset_ids[$asset_name]}" \
      -H 'Accept: application/octet-stream' > "$verify_dir/$asset_name" || { rm -rf -- "$verify_dir"; return 1; }
    actual_bytes="$(stat -c '%s' "$verify_dir/$asset_name")" || { rm -rf -- "$verify_dir"; return 1; }
    [[ "$actual_bytes" == "${remote_asset_bytes[$asset_name]}" ]] || {
      printf 'Published asset metadata size mismatch: %s.\n' "$asset_name" >&2
      rm -rf -- "$verify_dir"
      return 1
    }
  done
  gh release verify "$RELEASE_TAG" --repo "$REPOSITORY" >/dev/null || { rm -rf -- "$verify_dir"; return 1; }
  for asset_name in "${expected_names[@]}"; do
    gh release verify-asset "$RELEASE_TAG" "$verify_dir/$asset_name" \
      --repo "$REPOSITORY" >/dev/null || { rm -rf -- "$verify_dir"; return 1; }
  done
  canonical_text="$(jq -er --arg sha "$GITHUB_SHA" \
    'select(type == "object" and .schemaVersion == 1 and .sourceSha == $sha) | .runId,.runAttempt' \
    "$verify_dir/canonical-owner.json")" || { rm -rf -- "$verify_dir"; return 1; }
  readarray -t canonical_identity <<<"$canonical_text"
  (( ${#canonical_identity[@]} == 2 )) || { rm -rf -- "$verify_dir"; return 1; }
  canonical_run_id="${canonical_identity[0]}"
  canonical_run_attempt="${canonical_identity[1]}"
  canonical_backend_digest="$(jq -er '.digest' "$verify_dir/candidate-commitment-backend.json")" || { rm -rf -- "$verify_dir"; return 1; }
  canonical_frontend_digest="$(jq -er '.digest' "$verify_dir/candidate-commitment-frontend.json")" || { rm -rf -- "$verify_dir"; return 1; }
  verify_candidate_archive_commitment "$verify_dir/candidate-oci-backend.tar" \
    "$verify_dir/candidate-commitment-backend.json" backend "$GITHUB_SHA" || { rm -rf -- "$verify_dir"; return 1; }
  verify_candidate_archive_commitment "$verify_dir/candidate-oci-frontend.tar" \
    "$verify_dir/candidate-commitment-frontend.json" frontend "$GITHUB_SHA" || { rm -rf -- "$verify_dir"; return 1; }
  verify_canonical_provenance "$verify_dir" \
    "$verify_dir/release-provenance.json" "$verify_dir/release-assets.sigstore.json" \
    "$canonical_run_id" "$canonical_run_attempt" "$GITHUB_SHA" "refs/tags/$RELEASE_TAG" \
    "$REPOSITORY/.github/workflows/publish-images.yml" \
    "$REPOSITORY/.github/workflows/publish-images.yml@refs/tags/$RELEASE_TAG" \
    "$canonical_backend_digest" "$canonical_frontend_digest" || { rm -rf -- "$verify_dir"; return 1; }
  for asset_name in "${expected_names[@]}"; do
    attestation_args=()
    case "$asset_name" in
      release-provenance.json|release-assets.sigstore.json) ;;
      *) attestation_args=(--bundle "$verify_dir/release-assets.sigstore.json"
          --predicate-type https://yunlume.example/attestations/release-invocation/v1) ;;
    esac
    gh attestation verify "$verify_dir/$asset_name" \
      "${attestation_args[@]}" \
      --repo "$REPOSITORY" \
      --signer-workflow "$REPOSITORY/.github/workflows/publish-images.yml" \
      --source-ref "refs/tags/$RELEASE_TAG" \
      --source-digest "$GITHUB_SHA" >/dev/null || {
        rm -rf -- "$verify_dir"
        return 1
      }
  done
  verify_release_checksums "$verify_dir" "$archive_name" || {
    rm -rf -- "$verify_dir"
    return 1
  }
  python3 - "$verify_dir" "$GITHUB_SHA" "$RELEASE_TAG" "$OWNER" "$BACKEND_DIGEST" "$FRONTEND_DIGEST" <<'PY'
import hashlib, json, re, sys
from pathlib import Path

root = Path(sys.argv[1])
github_sha, tag, owner, backend_digest, frontend_digest = sys.argv[2:]
version = tag.removeprefix("v")
archive_name = f"yunlume-host-{tag}.tar.gz"
asset_names = {
    "install.sh", "release-manifest.json", "yunlume-compose.yml",
    archive_name, f"{archive_name}.sha256", "SHA256SUMS",
    "candidate-commitment-backend.json", "candidate-commitment-frontend.json",
    "candidate-oci-backend.tar", "candidate-oci-frontend.tar", "canonical-owner.json", "backend-jar-producer.json",
}
baseline = json.loads((root / "release-provenance.json").read_text(encoding="utf-8"))
if set(baseline) != {"schemaVersion", "canonicalIdentity", "attestationBundleSha256", "predicateSha256", "backendJarSha256", "assets", "manifest"}:
    raise SystemExit("release provenance schema mismatch")
if baseline["schemaVersion"] != 3:
    raise SystemExit("release provenance identity mismatch")
if re.fullmatch(r"[0-9a-f]{64}", baseline["backendJarSha256"]) is None:
    raise SystemExit("release provenance backend JAR digest mismatch")
if set(baseline["assets"]) != asset_names:
    raise SystemExit("release provenance asset set mismatch")
for name in sorted(asset_names):
    content = (root / name).read_bytes()
    record = baseline["assets"][name]
    if set(record) != {"sha256", "size"} or record["sha256"] != hashlib.sha256(content).hexdigest():
        raise SystemExit(f"release provenance digest mismatch: {name}")
    if record["size"] != len(content):
        raise SystemExit(f"release provenance size mismatch: {name}")
manifest = json.loads((root / "release-manifest.json").read_text(encoding="utf-8"))
if baseline["manifest"] != manifest:
    raise SystemExit("release provenance manifest mismatch")
if set(manifest) != {"version", "compatibilityEpoch", "docker", "host"} or manifest.get("version") != version:
    raise SystemExit("published manifest version mismatch")
if manifest.get("compatibilityEpoch") != 1:
    raise SystemExit("manifest compatibilityEpoch mismatch")
docker, host = manifest.get("docker"), manifest.get("host")
if not isinstance(docker, dict) or set(docker) != {"compose", "composeSha256", "backendImage", "frontendImage"}:
    raise SystemExit("published manifest docker schema mismatch")
if not isinstance(host, dict) or set(host) != {"archive", "archiveSha256"}:
    raise SystemExit("published manifest host schema mismatch")
if docker["compose"] != "yunlume-compose.yml" or host["archive"] != archive_name:
    raise SystemExit("manifest asset name mismatch")
digest = lambda path: hashlib.sha256((root / path).read_bytes()).hexdigest()
if docker["composeSha256"] != digest("yunlume-compose.yml"):
    raise SystemExit("manifest composeSha256 mismatch")
archive_sha = digest(archive_name)
if host["archiveSha256"] != archive_sha:
    raise SystemExit("manifest archiveSha256 mismatch")
if (root / f"{archive_name}.sha256").read_text(encoding="utf-8") != f"{archive_sha}  {archive_name}\n":
    raise SystemExit("archive sidecar mismatch")
expected_images = {"backend": backend_digest, "frontend": frontend_digest}
for component, expected in expected_images.items():
    wanted = f"ghcr.io/{owner}/yunlume-{component}@{expected}"
    if docker[f"{component}Image"] != wanted or re.fullmatch(r"sha256:[0-9a-f]{64}", expected) is None:
        raise SystemExit(f"published manifest {component} digest mismatch")
PY
  local python_status=$?
  if (( python_status != 0 )); then
    rm -rf -- "$verify_dir"
    return "$python_status"
  fi
  BACKEND_JAR_SHA="$(jq -r '.backendJarSha256' "$verify_dir/release-provenance.json")" || { rm -rf -- "$verify_dir"; return 1; }
  python3 ops/validate-artifact-producer.py "$verify_dir/backend-jar-producer.json" "$GITHUB_SHA" >/dev/null || { rm -rf -- "$verify_dir"; return 1; }
  [[ "$(jq -er '.jarSha256' "$verify_dir/backend-jar-producer.json")" == "$BACKEND_JAR_SHA" ]] || { rm -rf -- "$verify_dir"; return 1; }
  verify_host_archive_jar_sha "$verify_dir/$archive_name" "$BACKEND_JAR_SHA" || { rm -rf -- "$verify_dir"; return 1; }
  rm -rf -- "$verify_dir"

  verify_immutable_image() {
    local verify_component="$1" expected_digest="$2" actual_digest
    actual_digest="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' \
      "ghcr.io/${OWNER}/yunlume-${verify_component}:${VERSION}")" || return
    [[ "$actual_digest" == "$expected_digest" ]] || {
      printf 'Published %s immutable digest mismatch.\n' "$verify_component" >&2
      return 1
    }
    if [[ "$verify_component" == backend ]]; then
      verify_container_jar_sha "ghcr.io/${OWNER}/yunlume-backend@${actual_digest}" "$BACKEND_JAR_SHA"
    fi
  }
  verify_immutable_image backend "$BACKEND_DIGEST" || return
  verify_immutable_image frontend "$FRONTEND_DIGEST" || return

  assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1
}

if [[ "$release_draft" == false ]]; then
  [[ "$PREFLIGHT_PUBLISHED" == true ]] || {
    printf 'Release became published after preflight; refusing mutation.\n' >&2
    exit 1
  }
  BACKEND_DIGEST="$PREFLIGHT_BACKEND_DIGEST"
  FRONTEND_DIGEST="$PREFLIGHT_FRONTEND_DIGEST"
  [[ "$BACKEND_DIGEST" =~ ^sha256:[0-9a-f]{64}$ &&
     "$FRONTEND_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || {
    printf 'Published release preflight did not provide immutable image digests.\n' >&2
    exit 1
  }
  verify_published_release
  minor_version="${VERSION%.*}"
  for component in backend frontend; do
    case "$component" in
      backend) expected_alias_digest="$BACKEND_DIGEST" ;;
      frontend) expected_alias_digest="$FRONTEND_DIGEST" ;;
    esac
    alias_digest="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' \
      "ghcr.io/${OWNER}/yunlume-${component}:${minor_version}")"
    [[ "$alias_digest" == "$expected_alias_digest" ]] || {
      printf 'Published rerun found missing or mismatched %s rolling alias; refusing mutation.\n' "$component" >&2
      exit 1
    }
  done
  printf 'Published release verified without external mutation.\n'
  exit 0
else
  [[ "$release_draft" == true && "$PREFLIGHT_PUBLISHED" != true ]] || {
    printf 'Draft release state did not converge with preflight.\n' >&2
    exit 1
  }
  for asset in "${expected_assets[@]}"; do
    asset_name="${asset##*/}"
    [[ "$asset_name" != canonical-owner.json ]] || continue
    if [[ "$CANONICAL_CURRENT" != true || "$asset_name" == backend-jar-producer.json || "$asset_name" == candidate-commitment-*.json || "$asset_name" == candidate-oci-*.tar ]]; then
      asset_id="$(list_release_asset_ids "$REPOSITORY" "$release_id" "$asset_name")"
      [[ -n "$asset_id" && "$asset_id" != *$'\n'* ]] || {
        printf 'Canonical draft asset is missing or duplicated: %s.\n' "$asset_name" >&2; exit 1;
      }
      check_file="$(mktemp)"
      gh api "repos/$REPOSITORY/releases/assets/$asset_id" \
        -H 'Accept: application/octet-stream' > "$check_file"
      cmp -- "$asset" "$check_file" || {
        rm -f -- "$check_file"
        printf 'Canonical draft asset differs; refusing replacement: %s.\n' "$asset_name" >&2
        exit 1
      }
      rm -f -- "$check_file"
    else
      upload_release_asset_by_id "$REPOSITORY" "$release_id" "$asset" \
        "$RELEASE_TAG" "$GITHUB_SHA" "$recovery_marker"
    fi
  done

  # Verify every canonical byte before final ownership.
  for asset in "${expected_assets[@]}"; do
    asset_name="${asset##*/}"
    [[ "$asset_name" != canonical-owner.json ]] || continue
    asset_id="$(list_release_asset_ids "$REPOSITORY" "$release_id" "$asset_name")"
    [[ -n "$asset_id" && "$asset_id" != *$'\n'* ]] || {
      printf 'Canonical draft asset is missing before ownership: %s.\n' "$asset_name" >&2; exit 1;
    }
    check_file="$(mktemp)"
    gh api "repos/$REPOSITORY/releases/assets/$asset_id" \
      -H 'Accept: application/octet-stream' > "$check_file"
    cmp -- "$asset" "$check_file" || {
      rm -f -- "$check_file"
      printf 'Canonical draft asset readback differs before ownership: %s.\n' "$asset_name" >&2
      exit 1
    }
    rm -f -- "$check_file"
  done

  verify_canonical_provenance release release/release-provenance.json \
    release/release-assets.sigstore.json "$CANONICAL_RUN_ID" "$CANONICAL_RUN_ATTEMPT" \
    "$GITHUB_SHA" "refs/tags/$RELEASE_TAG" \
    "$REPOSITORY/.github/workflows/publish-images.yml" \
    "$REPOSITORY/.github/workflows/publish-images.yml@refs/tags/$RELEASE_TAG" \
    "$BACKEND_DIGEST" "$FRONTEND_DIGEST"
  for asset in "${expected_assets[@]}"; do
    attestation_args=()
    case "${asset##*/}" in
      release-provenance.json|release-assets.sigstore.json) ;;
      *) attestation_args=(--bundle release/release-assets.sigstore.json
          --predicate-type https://yunlume.example/attestations/release-invocation/v1) ;;
    esac
    gh attestation verify "$asset" "${attestation_args[@]}" \
      --repo "$REPOSITORY" \
      --signer-workflow "$REPOSITORY/.github/workflows/publish-images.yml" \
      --source-ref "refs/tags/$RELEASE_TAG" \
      --source-digest "$GITHUB_SHA" >/dev/null
  done

  # This immutable-once owner record is deliberately the final draft
  # asset write. Before it, a later run may safely replace partial
  # canonical output; after it, every rerun must reuse these bytes.
  ensure_release_asset_once_by_id "$REPOSITORY" "$release_id" release/canonical-owner.json \
    "$RELEASE_TAG" "$GITHUB_SHA" "$recovery_marker"

  uploaded_names_text="$(gh api "repos/$REPOSITORY/releases/$release_id/assets" --paginate --jq '.[].name')"
  uploaded_names_text="$(sort <<< "$uploaded_names_text")"
  mapfile -t uploaded_names <<< "$uploaded_names_text"
  expected_names_text="$(printf '%s\n' "${expected_assets[@]##*/}" | sort)"
  mapfile -t expected_names <<< "$expected_names_text"
  if [[ "${uploaded_names[*]}" != "${expected_names[*]}" ]]; then
    printf 'Draft release asset set did not converge; leaving draft for recovery.\n' >&2
    exit 1
  fi

  verify_dir="$(mktemp -d)"
  trap 'rm -rf -- "$verify_dir"' EXIT
  for asset in "${expected_assets[@]}"; do
    asset_name="${asset##*/}"
    asset_id="$(list_release_asset_ids "$REPOSITORY" "$release_id" "$asset_name")"
    [[ -n "$asset_id" && "$asset_id" != *$'\n'* ]]
    gh api "repos/$REPOSITORY/releases/assets/$asset_id" \
      -H 'Accept: application/octet-stream' > "$verify_dir/$asset_name"
    cmp -- "$asset" "$verify_dir/$asset_name" || {
      printf 'Uploaded asset differs from local release output: %s\n' "${asset##*/}" >&2
      exit 1
    }
  done

  # Final remote tag identity guard: keep immediately before draft=false.
  assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1
  default_branch="$(gh api "repos/$REPOSITORY" --jq '.default_branch')"
  [[ -n "$default_branch" ]]
  default_branch_ref="$(jq -rn --arg value "$default_branch" '$value | @uri')"
  default_branch_status="$(gh api "repos/$REPOSITORY/compare/$GITHUB_SHA...$default_branch_ref" \
    --jq '.status')"
  [[ "$default_branch_status" == "ahead" || "$default_branch_status" == "identical" ]] || {
    printf 'Tagged commit is no longer reachable from the approved default branch.\n' >&2
    exit 1
  }

  publish_release_by_id "$REPOSITORY" "$release_id" \
    "$RELEASE_TAG" "$GITHUB_SHA" "$recovery_marker"
fi

# Full immutable Release verification: keep immediately before rolling aliases.
verify_published_release

# The major.minor alias is intentionally rolling and is promoted only
# after the immutable Release, every attestation-bound byte, complete
# manifest contract, immutable image tags, and source tag are re-read.
minor_version="${VERSION%.*}"
for component in backend frontend; do
  case "$component" in
    backend) candidate_digest="$BACKEND_DIGEST" ;;
    frontend) candidate_digest="$FRONTEND_DIGEST" ;;
  esac
  image="ghcr.io/${OWNER}/yunlume-${component}"
  docker buildx imagetools create \
    --tag "${image}:${minor_version}" \
    "${image}@${candidate_digest}"
  rolling_digest="$(docker buildx imagetools inspect \
    --format '{{.Manifest.Digest}}' "${image}:${minor_version}")"
  if [[ "$rolling_digest" != "$candidate_digest" ]]; then
    case "$component" in
      backend) printf 'Rolling alias backend digest mismatch.\n' >&2 ;;
      frontend) printf 'Rolling alias frontend digest mismatch.\n' >&2 ;;
    esac
    exit 1
    fi
  done
