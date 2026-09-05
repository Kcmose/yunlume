#!/usr/bin/env bash
# 新运行与重跑共用完整只读门禁，不能信任旧 attempt 缓存的 published=false。
set -euo pipefail
source ops/lib/release-transaction.sh
source ops/lib/publish-workflow.sh
assert_pinned_gh_capabilities
if [[ ! "$RELEASE_TAG" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  printf 'Release tag must use vX.Y.Z without leading zeroes: %s\n' "$RELEASE_TAG" >&2
  exit 1
fi
assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1
default_branch="$(gh api "repos/$REPOSITORY" --jq '.default_branch')"
encoded_default_branch="$(jq -rn --arg value "$default_branch" '$value|@uri')"
default_branch_status="$(gh api \
  "repos/$REPOSITORY/compare/$GITHUB_SHA...$encoded_default_branch" --jq '.status')"
[[ "$default_branch_status" == ahead || "$default_branch_status" == identical ]] || {
  printf 'Release commit is not in the approved default-branch history.\n' >&2
  exit 1
}

releases_json="$(gh api -H 'X-GitHub-Api-Version: 2026-03-10' --paginate "repos/$REPOSITORY/releases")"
matching_text="$(jq --arg tag "$RELEASE_TAG" -r \
  '.[] | select(.tag_name == $tag) | [.id, .draft, .target_commitish, .prerelease, .immutable] | @tsv' \
  <<<"$releases_json")"
matching_releases=()
[[ -z "$matching_text" ]] || mapfile -t matching_releases <<<"$matching_text"
if (( ${#matching_releases[@]} > 1 )); then
  printf 'Multiple releases use tag %s; refusing ambiguous recovery.\n' "$RELEASE_TAG" >&2
  exit 1
fi
published_release=false
if (( ${#matching_releases[@]} == 1 )); then
  IFS=$'\t' read -r existing_release_id existing_release_draft existing_release_target existing_release_prerelease existing_release_immutable \
    <<< "${matching_releases[0]}"
  release_body="$(gh api "repos/$REPOSITORY/releases/$existing_release_id" --jq '.body // ""')"
  recovery_marker="<!-- yunlume-release-reservation-v3:${GITHUB_SHA} -->"
  [[ "$release_body" == "$recovery_marker" ]] || {
    printf 'Existing release does not have the exact unowned reservation marker for %s.\n' "$GITHUB_SHA" >&2
    exit 1
  }
  resolve_target_commitish() {
    local encoded_target
    encoded_target="$(jq -rn --arg value "$1" '$value|@uri')" || return
    gh api "repos/$REPOSITORY/commits/$encoded_target" --jq '.sha'
  }
  resolved_release_target="$(resolve_target_commitish "$existing_release_target")"
  [[ "$resolved_release_target" == "$GITHUB_SHA" ]] || {
    printf 'Existing release target %s resolves to %s, not workflow commit %s.\n' \
      "$existing_release_target" "$resolved_release_target" "$GITHUB_SHA" >&2
    exit 1
  }
  [[ "$existing_release_prerelease" == false ]] || {
    printf 'Existing release is unexpectedly marked as a prerelease.\n' >&2
    exit 1
  }
  if [[ "$existing_release_draft" == false ]]; then
    [[ "$existing_release_immutable" == true ]] || {
      printf 'Published Release is mutable; immutable releases are required.\n' >&2
      exit 1
    }
    published_release=true
    archive_name="yunlume-host-${RELEASE_TAG}.tar.gz"
    expected_names=(install.sh release-manifest.json release-provenance.json release-assets.sigstore.json canonical-owner.json backend-jar-producer.json candidate-commitment-backend.json candidate-commitment-frontend.json candidate-oci-backend.tar candidate-oci-frontend.tar yunlume-compose.yml "$archive_name" "$archive_name.sha256" SHA256SUMS)
    expected_names_text="$(printf '%s\n' "${expected_names[@]}" | sort)"
    mapfile -t expected_names_sorted <<< "$expected_names_text"
    # 即使 CLI 输出了完整数据后失败，也必须拒绝，不能让 process substitution 吞掉退出码。
    remote_asset_text="$(gh api "repos/$REPOSITORY/releases/$existing_release_id/assets" --paginate \
      --jq '.[] | [.name, .size, .id] | @tsv')"
    remote_asset_text="$(sort <<< "$remote_asset_text")"
    mapfile -t remote_asset_rows <<< "$remote_asset_text"
    remote_names=()
    declare -A remote_asset_bytes=() remote_asset_ids=()
    for row in "${remote_asset_rows[@]}"; do
      IFS=$'\t' read -r asset_name asset_bytes asset_id <<< "$row"
      [[ "$asset_bytes" =~ ^[1-9][0-9]*$ ]] || {
        printf 'Published asset %s has invalid or empty byte metadata.\n' "$asset_name" >&2
        exit 1
      }
      remote_names+=("$asset_name")
      remote_asset_bytes["$asset_name"]="$asset_bytes"
      remote_asset_ids["$asset_name"]="$asset_id"
    done
    [[ "${remote_names[*]}" == "${expected_names_sorted[*]}" ]] || {
      printf 'Published release asset set is incomplete or contains unexpected files.\n' >&2
      exit 1
    }
    verify_dir="$(mktemp -d)"
    trap 'rm -rf -- "$verify_dir"' EXIT
    for asset_name in "${expected_names[@]}"; do
      gh api "repos/$REPOSITORY/releases/assets/${remote_asset_ids[$asset_name]}" \
        -H 'Accept: application/octet-stream' > "$verify_dir/$asset_name"
      actual_bytes="$(stat -c '%s' "$verify_dir/$asset_name")"
      [[ "$actual_bytes" == "${remote_asset_bytes[$asset_name]}" ]] || {
        printf 'Published asset byte count changed: %s.\n' "$asset_name" >&2
        exit 1
      }
    done
    gh release verify "$RELEASE_TAG" --repo "$REPOSITORY" >/dev/null
    for asset_name in "${expected_names[@]}"; do
      gh release verify-asset "$RELEASE_TAG" "$verify_dir/$asset_name" \
        --repo "$REPOSITORY" >/dev/null
    done
    canonical_text="$(jq -er --arg sha "$GITHUB_SHA" \
      'select(type == "object" and .schemaVersion == 1 and .sourceSha == $sha) | .runId,.runAttempt' \
      "$verify_dir/canonical-owner.json")"
    readarray -t canonical_identity <<<"$canonical_text"
    (( ${#canonical_identity[@]} == 2 )) || exit 1
    canonical_run_id="${canonical_identity[0]}"
    canonical_run_attempt="${canonical_identity[1]}"
    canonical_backend_digest="$(jq -er '.digest' "$verify_dir/candidate-commitment-backend.json")"
    canonical_frontend_digest="$(jq -er '.digest' "$verify_dir/candidate-commitment-frontend.json")"
    verify_candidate_archive_commitment "$verify_dir/candidate-oci-backend.tar" \
      "$verify_dir/candidate-commitment-backend.json" backend "$GITHUB_SHA"
    verify_candidate_archive_commitment "$verify_dir/candidate-oci-frontend.tar" \
      "$verify_dir/candidate-commitment-frontend.json" frontend "$GITHUB_SHA"
    verify_canonical_provenance "$verify_dir" "$verify_dir/release-provenance.json" \
      "$verify_dir/release-assets.sigstore.json" "$canonical_run_id" \
      "$canonical_run_attempt" "$GITHUB_SHA" "refs/tags/$RELEASE_TAG" \
      "$REPOSITORY/.github/workflows/publish-images.yml" \
      "$REPOSITORY/.github/workflows/publish-images.yml@refs/tags/$RELEASE_TAG" \
      "$canonical_backend_digest" "$canonical_frontend_digest"
    for asset_name in "${expected_names[@]}"; do
      attestation_args=()
      case "$asset_name" in
        release-provenance.json|release-assets.sigstore.json) ;;
        *) attestation_args=(--bundle "$verify_dir/release-assets.sigstore.json") ;;
      esac
      gh attestation verify "$verify_dir/$asset_name" \
        "${attestation_args[@]}" \
        --repo "$REPOSITORY" \
        --signer-workflow "$REPOSITORY/.github/workflows/publish-images.yml" \
        --source-ref "refs/tags/$RELEASE_TAG" \
        --source-digest "$GITHUB_SHA" >/dev/null
    done
    verify_release_checksums "$verify_dir" "$archive_name"
    manifest_values="$(python3 - "$verify_dir" "$GITHUB_SHA" "$RELEASE_TAG" "$REPOSITORY" <<'PY'
import hashlib, json, re, sys
from pathlib import Path

root, github_sha, tag, repository = Path(sys.argv[1]), *sys.argv[2:]
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
docker = manifest.get("docker")
host = manifest.get("host")
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
owner = repository.split("/", 1)[0].lower()
for component in ("backend", "frontend"):
    value = docker[f"{component}Image"]
    match = re.fullmatch(rf"ghcr\.io/{re.escape(owner)}/yunlume-{component}@(sha256:[0-9a-f]{{64}})", value)
    if match is None:
        raise SystemExit("published manifest image is not immutable or belongs to another owner")
    print(match.group(1))
PY
    )"
    mapfile -t published_digests <<< "$manifest_values"
    (( ${#published_digests[@]} == 2 )) || exit 1
    published_backend_digest="${published_digests[0]}"
    published_frontend_digest="${published_digests[1]}"
    published_backend_jar_sha="$(jq -r '.backendJarSha256' "$verify_dir/release-provenance.json")"
    # 已发布验证不要求 Actions artifact 仍在保留期内；签名资产中的描述仍须绑定相同源码和 JAR。
    python3 ops/validate-artifact-producer.py "$verify_dir/backend-jar-producer.json" "$GITHUB_SHA" >/dev/null
    [[ "$(jq -er '.jarSha256' "$verify_dir/backend-jar-producer.json")" == "$published_backend_jar_sha" ]]
    verify_host_archive_jar_sha "$verify_dir/$archive_name" "$published_backend_jar_sha"
    owner="${REPOSITORY%%/*}"
    owner="$(printf '%s' "$owner" | tr '[:upper:]' '[:lower:]')"
    verify_immutable_image() {
      local component="$1" expected_digest="$2" actual_digest
      actual_digest="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' \
        "ghcr.io/${owner}/yunlume-${component}:${RELEASE_TAG#v}")"
      [[ "$actual_digest" == "$expected_digest" ]] || {
        printf 'Published %s immutable tag digest mismatch.\n' "$component" >&2
        exit 1
      }
      if [[ "$component" == backend ]]; then
        verify_container_jar_sha "ghcr.io/${owner}/yunlume-backend@${actual_digest}" "$published_backend_jar_sha"
      fi
    }
    verify_immutable_image backend "$published_backend_digest"
    verify_immutable_image frontend "$published_frontend_digest"
    verify_rolling_aliases "$owner" "${RELEASE_TAG#v}" "$published_backend_digest" "$published_frontend_digest"
    printf 'backend_digest=%s\nfrontend_digest=%s\n' \
      "$published_backend_digest" "$published_frontend_digest" >> "$GITHUB_OUTPUT"
  elif [[ "$existing_release_draft" != true ]]; then
    printf 'Existing release state is invalid.\n' >&2
    exit 1
  fi
fi
printf 'published_release=%s\n' "$published_release" >> "$GITHUB_OUTPUT"

published_text="$(jq --arg tag "$RELEASE_TAG" -r \
  '.[] | select(.draft == false and .tag_name != $tag) | .tag_name' <<<"$releases_json")"
published_tags=()
[[ -z "$published_text" ]] || mapfile -t published_tags <<<"$published_text"
python3 ops/validate-release-version.py "$RELEASE_TAG" "${published_tags[@]}"
