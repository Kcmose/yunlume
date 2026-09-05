#!/usr/bin/env bash
# Fail-closed primitives used by the release workflow and behavioral tests.

readonly YUNLUME_GH_VERSION="2.93.0"

assert_pinned_gh_capabilities() {
  local version_output actual
  version_output="$(gh version)" || {
    printf 'Pinned GitHub CLI %s is unavailable. Run ops/install-pinned-gh.sh first.\n' "$YUNLUME_GH_VERSION" >&2
    return 1
  }
  if [[ "$version_output" =~ ^gh\ version\ ([0-9]+\.[0-9]+\.[0-9]+)([[:space:]]|$) ]]; then
    actual="${BASH_REMATCH[1]}"
  else
    printf 'Unable to parse GitHub CLI version output.\n' >&2
    return 1
  fi
  [[ "$actual" == "$YUNLUME_GH_VERSION" ]] || {
    printf 'GitHub CLI %s is required; found %s. Run ops/install-pinned-gh.sh first.\n' \
      "$YUNLUME_GH_VERSION" "$actual" >&2
    return 1
  }
  gh release verify --help >/dev/null 2>&1 &&
    gh release verify-asset --help >/dev/null 2>&1 &&
    gh attestation verify --help >/dev/null 2>&1 || {
      printf 'Pinned gh lacks release verify, release verify-asset, or attestation verify.\n' >&2
      return 1
    }
}

validate_release_asset_name() {
  local name="${1-}"
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ && "$name" != .* && "$name" != *..* ]] || {
    printf 'Invalid release asset name: only 1-128 ASCII letters, digits, dot, underscore, and hyphen are allowed.\n' >&2
    return 1
  }
}

list_release_asset_ids() {
  local repository="$1" release_id="$2" name="$3" response
  validate_release_asset_name "$name" || return
  # API失败可能同时输出一部分合法JSON；解析成功不能覆盖API的失败状态。
  response="$(gh api "repos/$repository/releases/$release_id/assets" --paginate --slurp)" || return
  python3 -c '
import json, sys
name = sys.argv[1]
data = json.load(sys.stdin)
if not isinstance(data, list): raise SystemExit("release asset response is not a JSON array")
items = [item for page in data for item in page] if data and all(isinstance(page, list) for page in data) else data
for item in items:
    if not isinstance(item, dict): raise SystemExit("release asset response item is invalid")
    if item.get("name") == name:
        value = item.get("id")
        if type(value) is not int or value <= 0: raise SystemExit("release asset ID is invalid")
        print(value)
' "$name" <<<"$response"
}

oci_archive_file_sha256() {
  local archive="$1" digest
  digest="$(sha256sum -- "$archive")" || return
  printf '%s\n' "${digest%% *}"
}

oci_archive_root_digest() {
  local archive="$1" tmp status
  tmp="$(mktemp -d)" || return
  if ! tar -xf "$archive" -C "$tmp"; then
    rm -rf -- "$tmp"
    return 1
  fi
  if python3 - "$tmp" <<'PY'
import hashlib, json, re, sys
from pathlib import Path
root = Path(sys.argv[1])
index_path = root / "index.json"
if not index_path.is_file():
    index_path = root / "." / "index.json"
try:
    index = json.loads(index_path.read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"invalid OCI index.json: {exc}")
manifests = index.get("manifests")
if not isinstance(manifests, list) or len(manifests) != 1:
    raise SystemExit("OCI archive must contain exactly one root descriptor")
descriptor = manifests[0]
digest = descriptor.get("digest")
if not isinstance(digest, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
    raise SystemExit("OCI root descriptor has invalid SHA-256 digest")
blob = root / "blobs" / "sha256" / digest.removeprefix("sha256:")
if not blob.is_file():
    raise SystemExit("OCI root descriptor blob is missing")
content = blob.read_bytes()
if hashlib.sha256(content).hexdigest() != digest.removeprefix("sha256:"):
    raise SystemExit("OCI root descriptor blob digest mismatch")
if descriptor.get("size") != len(content):
    raise SystemExit("OCI root descriptor blob size mismatch")
print(digest)
PY
  then status=0
  else status=$?
  fi
  rm -rf -- "$tmp" || return
  return "$status"
}

verify_candidate_archive_commitment() {
  local archive="$1" commitment="$2" component="$3" source_sha="$4"
  local -a values=()
  local actual_sha actual_size actual_root json_values
  json_values="$(python3 - "$commitment" "$component" "$source_sha" <<'PY'
import json, re, sys
from pathlib import Path
try:
    value=json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"invalid candidate commitment JSON: {exc}")
component, source_sha=sys.argv[2:]
required={"archiveSha256","archiveSize","component","digest","schemaVersion","sourceSha"}
if not isinstance(value, dict) or set(value) != required or value["schemaVersion"] != 2:
    raise SystemExit("candidate commitment schema mismatch")
if value["component"] != component or value["sourceSha"] != source_sha:
    raise SystemExit("candidate commitment identity mismatch")
if re.fullmatch(r"sha256:[0-9a-f]{64}", str(value["digest"])) is None:
    raise SystemExit("candidate root digest is invalid")
if re.fullmatch(r"[0-9a-f]{64}", str(value["archiveSha256"])) is None:
    raise SystemExit("candidate archive SHA-256 is invalid")
if type(value["archiveSize"]) is not int or value["archiveSize"] <= 0:
    raise SystemExit("candidate archive size is invalid")
print(value["digest"], value["archiveSha256"], value["archiveSize"], sep="\n")
PY
)" || return
  readarray -t values <<<"$json_values"
  (( ${#values[@]} == 3 )) || return 1
  actual_sha="$(oci_archive_file_sha256 "$archive")" || return
  actual_size="$(stat -c '%s' "$archive")" || return
  actual_root="$(oci_archive_root_digest "$archive")" || return
  [[ "$actual_root" == "${values[0]}" && "$actual_sha" == "${values[1]}" && "$actual_size" == "${values[2]}" ]] || {
    printf 'Durable OCI archive bytes do not match candidate commitment.\n' >&2
    return 1
  }
}

candidate_registry_digest() {
  local image="$1" tag="$2" result
  if result="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' "${image}:${tag}" 2>&1)"; then
    printf '%s\n' "$result"
    return 0
  fi
  case "${result,,}" in
    *'403'*|*'401'*|*'denied'*|*'unauthorized'*|*'timeout'*|*'timed out'*) ;;
    *'manifest unknown'*) return 44 ;;
    *)
      # 只把目标manifest的明确不存在视为可新建；凭据helper/网络组件的not found不是缺少候选。
      [[ "$result" != *"${image}:${tag}: not found" ]] || return 44
      ;;
  esac
  printf 'Unable to inspect candidate %s:%s: %s\n' "$image" "$tag" "$result" >&2
  return 1
}

candidate_copy_to_registry() {
  local image="$1" tag="$2" archive="$3"
  skopeo copy --all --preserve-digests "oci-archive:${archive}" "docker://${image}:${tag}"
}

candidate_verify_attestation() {
  local image="$1" digest="$2"
  gh attestation verify "oci://${image}@${digest}" \
    --repo "$REPOSITORY" \
    --signer-workflow "$REPOSITORY/.github/workflows/publish-images.yml" \
    --source-ref "refs/tags/$RELEASE_TAG" \
    --source-digest "$GITHUB_SHA" >/dev/null
}

# A registry manifest/tag PUT is the transaction commit point. skopeo may upload
# unreferenced blobs/manifests before it, but the candidate is not published or
# discoverable until the final named manifest succeeds. The commit/component
# scoped reference is therefore the durable known-address digest index.
publish_candidate_transaction() {
  local image="$1" tag="$2" archive="$3" expected_digest="$4"
  local current root published status
  [[ "$expected_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  if current="$(candidate_registry_digest "$image" "$tag")"; then
    [[ "$current" == "$expected_digest" ]] || {
      printf 'Candidate reference moved: expected %s, got %s.\n' "$expected_digest" "$current" >&2
      return 1
    }
    candidate_verify_attestation "$image" "$current" || return
    printf '%s\n' "$current"
    return 0
  else
    status=$?
    (( status == 44 )) || return "$status"
  fi

  root="$(oci_archive_root_digest "$archive")" || return
  [[ "$root" == "$expected_digest" ]] || {
    printf 'OCI root digest %s differs from Buildx digest %s (archive file SHA-256 is not an OCI digest).\n' \
      "$root" "$expected_digest" >&2
    return 1
  }
  candidate_copy_to_registry "$image" "$tag" "$archive" "$expected_digest" || return
  published="$(candidate_registry_digest "$image" "$tag")" || return
  [[ "$published" == "$expected_digest" ]] || {
    printf 'Post-skopeo candidate digest %s differs from OCI root %s.\n' "$published" "$expected_digest" >&2
    return 1
  }
  candidate_verify_attestation "$image" "$published" || return
  printf '%s\n' "$published"
}

assert_expected_draft_release_by_id() {
  local repository="$1" release_id="$2" expected_tag="$3" expected_sha="$4" expected_marker="$5"
  local state actual_id draft prerelease tag target marker immutable encoded_target resolved
  state="$(gh api -H 'X-GitHub-Api-Version: 2026-03-10' \
    "repos/$repository/releases/$release_id" \
    --jq '[.id, .draft, .prerelease, .tag_name, .target_commitish, (.body // ""), .immutable] | @tsv')" || return
  IFS=$'\t' read -r actual_id draft prerelease tag target marker immutable <<< "$state"
  [[ "$actual_id" == "$release_id" && "$draft" == true && "$prerelease" == false &&
     "$tag" == "$expected_tag" && "$marker" == "$expected_marker" && "$immutable" == false ]] || {
    printf 'Exact Release ID %s is no longer the expected draft; refusing mutation.\n' "$release_id" >&2
    return 1
  }
  encoded_target="$(python3 - "$target" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)" || return
  resolved="$(gh api "repos/$repository/commits/$encoded_target" --jq '.sha')" || return
  [[ "$resolved" == "$expected_sha" ]] || {
    printf 'Exact Release ID %s target no longer resolves to %s.\n' "$release_id" "$expected_sha" >&2
    return 1
  }
}

# Archive, commitment, and canonical-owner records are immutable-once-written.
# An equal existing asset is recovery success; different bytes are a conflict.
ensure_release_asset_once_by_id() {
  local repository="$1" release_id="$2" path="$3" tag="$4" sha="$5" marker="$6"
  local name encoded_name existing_id readback
  name="${path##*/}"
  validate_release_asset_name "$name" || return
  existing_id="$(list_release_asset_ids "$repository" "$release_id" "$name")" || return
  [[ "$existing_id" != *$'\n'* ]] || {
    printf 'Multiple immutable-once assets named %s exist.\n' "$name" >&2
    return 1
  }
  assert_expected_draft_release_by_id "$repository" "$release_id" "$tag" "$sha" "$marker" || return
  if [[ -n "$existing_id" ]]; then
    readback="$(mktemp)" || return
    gh api "repos/$repository/releases/assets/$existing_id" \
      -H 'Accept: application/octet-stream' > "$readback" || { rm -f -- "$readback"; return 1; }
    cmp -- "$path" "$readback" || {
      rm -f -- "$readback"
      printf 'Immutable-once asset differs; refusing replacement: %s.\n' "$name" >&2
      return 1
    }
    rm -f -- "$readback" || return
    return 0
  fi
  encoded_name="$(python3 - "$name" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)" || return
  gh api --hostname uploads.github.com --method POST \
    "repos/$repository/releases/$release_id/assets?name=$encoded_name" \
    -H 'Content-Type: application/octet-stream' --input "$path" >/dev/null
}

candidate_recovery_action() {
  local commitment="$1" archive="$2" locator="$3"
  case "$commitment:$archive:$locator" in
    false:false:false) printf 'build-and-stage\n' ;;
    false:true:false)  printf 'finish-commitment\n' ;;
    true:true:false)   printf 'publish-archive\n' ;;
    true:false:true|true:true:true) printf 'reuse-locator\n' ;;
    true:false:false)
      printf 'Committed candidate has neither locator nor durable OCI archive; recovery is impossible.\n' >&2
      return 1
      ;;
    *)
      printf 'Unsafe candidate state: locator exists without its immutable commitment.\n' >&2
      return 1
      ;;
  esac
}

canonical_ownership_action() {
  local finalized_owner="$1" current_run="$2" current_attempt="$3"
  local current="${current_run}:${current_attempt}"
  if [[ -z "$finalized_owner" ]]; then
    printf 'finalize-current\n'
  elif [[ "$finalized_owner" == "$current" ]]; then
    printf 'reuse-finalized\n'
  else
    printf 'Canonical ownership is finalized by %s; run %s may not substitute assets.\n' \
      "$finalized_owner" "$current" >&2
    return 1
  fi
}

upload_release_asset_by_id() {
  local repository="$1" release_id="$2" path="$3" tag="$4" sha="$5" marker="$6"
  local name encoded_name existing_id
  name="${path##*/}"
  encoded_name="$(python3 - "$name" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)" || return
  existing_id="$(list_release_asset_ids "$repository" "$release_id" "$name")" || return
  if [[ -n "$existing_id" ]]; then
    [[ "$existing_id" != *$'\n'* ]] || {
      printf 'Multiple assets named %s exist on exact Release ID %s.\n' "$name" "$release_id" >&2
      return 1
    }
    # Exact-ID read is deliberately adjacent to the clobber delete.
    assert_expected_draft_release_by_id "$repository" "$release_id" "$tag" "$sha" "$marker" || return
    gh api --method DELETE "repos/$repository/releases/assets/$existing_id" || return
  fi
  # Exact-ID read is deliberately adjacent to the upload. Repository-level
  # immutable releases provide the server-side race barrier if another actor
  # publishes between this read and the POST.
  assert_expected_draft_release_by_id "$repository" "$release_id" "$tag" "$sha" "$marker" || return
  gh api --hostname uploads.github.com --method POST \
    "repos/$repository/releases/$release_id/assets?name=$encoded_name" \
    -H 'Content-Type: application/octet-stream' --input "$path" >/dev/null
}

publish_release_by_id() {
  local repository="$1" release_id="$2" tag="$3" sha="$4" marker="$5"
  # Exact-ID read is deliberately adjacent to PATCH draft=false.
  assert_expected_draft_release_by_id "$repository" "$release_id" "$tag" "$sha" "$marker" || return
  gh api -H 'X-GitHub-Api-Version: 2026-03-10' --method PATCH \
    "repos/$repository/releases/$release_id" \
    -F draft=false -f make_latest=true >/dev/null
}

select_committed_candidate_digest() {
  local committed="$1" locator="$2"
  [[ "$committed" =~ ^sha256:[0-9a-f]{64}$ && "$locator" =~ ^sha256:[0-9a-f]{64}$ ]] || {
    printf 'Candidate commitment or locator digest is invalid.\n' >&2
    return 1
  }
  [[ "$locator" == "$committed" ]] || {
    printf 'Candidate locator moved: exact draft commitment is %s, locator resolves to %s.\n' "$committed" "$locator" >&2
    return 1
  }
  printf '%s\n' "$committed"
}

recover_candidate_without_archive() {
  local committed="$1" locator="$2" archive="$3"
  [[ ! -e "$archive" ]] || {
    printf 'Recovery helper expects an absent OCI archive.\n' >&2
    return 1
  }
  select_committed_candidate_digest "$committed" "$locator"
}

verify_host_archive_jar_sha() {
  local archive="$1" expected_sha="$2"
  [[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  # 与package-host-release.sh的根级布局一致，不解压链接、不拼接重复成员的字节。
  python3 - "$archive" "$expected_sha" <<'PY'
import hashlib, sys, tarfile
expected_name = "backend/yunlume-backend.jar"
actual_sha = None
try:
    with tarfile.open(sys.argv[1], mode="r:gz", ignore_zeros=True) as archive:
        for member in archive:
            if member.name == "backend" and not member.isdir():
                raise ValueError("Host archive backend must be a directory")
            if member.name.replace("\\", "/").rstrip("/").rsplit("/", 1)[-1] != "yunlume-backend.jar":
                continue
            if actual_sha is not None or member.name != expected_name:
                raise ValueError("Host archive must contain exactly one root-level backend/yunlume-backend.jar")
            if member.type not in (tarfile.REGTYPE, tarfile.AREGTYPE) or member.size <= 0:
                raise ValueError("Host archive backend JAR must be a nonempty regular file")
            digest = hashlib.sha256()
            with archive.extractfile(member) as content:
                while chunk := content.read(1024 * 1024):
                    digest.update(chunk)
            actual_sha = digest.hexdigest()
    if actual_sha is None:
        raise ValueError("Host archive backend JAR is missing")
    if actual_sha != sys.argv[2]:
        raise ValueError(f"Host archive backend JAR SHA-256 {actual_sha} differs from tested JAR {sys.argv[2]}")
except (OSError, EOFError, tarfile.TarError, ValueError) as exc:
    raise SystemExit(str(exc))
PY
}

verify_container_jar_sha() {
  local image="$1" expected_sha="$2" result actual_sha
  [[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  result="$(docker run --rm --entrypoint sha256sum "$image" /app/app.jar)" || return
  actual_sha="${result%% *}"
  [[ "$actual_sha" == "$expected_sha" ]] || {
    printf 'Backend image JAR SHA-256 %s differs from tested JAR %s.\n' "$actual_sha" "$expected_sha" >&2
    return 1
  }
}

verify_canonical_provenance() {
  local root="$1" provenance="$2" bundle="$3" run_id="$4" run_attempt="$5"
  local source_sha="$6" source_ref="$7" workflow_identity="$8" workflow_ref="$9"
  local backend_digest="${10}" frontend_digest="${11}"
  python3 - "$root" "$provenance" "$bundle" "$run_id" "$run_attempt" \
    "$source_sha" "$source_ref" "$workflow_identity" "$workflow_ref" \
    "$backend_digest" "$frontend_digest" <<'PY'
import base64, hashlib, json, re, sys
from pathlib import Path
root, provenance_path, bundle_path = map(Path, sys.argv[1:4])
run_id, run_attempt, source_sha, source_ref, workflow_identity, workflow_ref, backend_digest, frontend_digest = sys.argv[4:]
provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
required = {"schemaVersion", "canonicalIdentity", "attestationBundleSha256", "predicateSha256", "backendJarSha256", "assets", "manifest"}
if set(provenance) != required or provenance["schemaVersion"] != 3:
    raise SystemExit("release provenance schema mismatch")
if re.fullmatch(r"[0-9a-f]{64}", str(provenance["backendJarSha256"])) is None or not isinstance(provenance["manifest"], dict):
    raise SystemExit("release provenance artifact identity mismatch")
bundle_bytes = bundle_path.read_bytes()
bundle_sha = hashlib.sha256(bundle_bytes).hexdigest()
if bundle_sha != provenance["attestationBundleSha256"]:
    raise SystemExit("canonical attestation bundle digest mismatch")
try:
    bundle = json.loads(bundle_bytes)
    envelope = bundle["dsseEnvelope"]
    if not envelope.get("signatures") or not bundle.get("verificationMaterial"):
        raise ValueError("signature or verification material absent")
    statement = json.loads(base64.b64decode(envelope["payload"], validate=True))
except Exception as exc:
    raise SystemExit(f"invalid canonical attestation bundle: {exc}")
if statement.get("predicateType") != "https://yunlume.example/attestations/release-invocation/v1":
    raise SystemExit("canonical predicate type mismatch")
predicate = statement.get("predicate")
if not isinstance(predicate, dict):
    raise SystemExit("canonical predicate missing")
predicate_bytes = json.dumps(predicate, separators=(",", ":"), sort_keys=True).encode()
predicate_sha = hashlib.sha256(predicate_bytes).hexdigest()
if predicate_sha != provenance["predicateSha256"]:
    raise SystemExit("canonical predicate digest mismatch")
expected_predicate = {
    "schemaVersion": 1, "githubRunId": run_id, "githubRunAttempt": run_attempt,
    "sourceSha": source_sha, "sourceRef": source_ref,
    "workflowIdentity": workflow_identity, "workflowRef": workflow_ref,
    "candidateDigests": {"backend": backend_digest, "frontend": frontend_digest},
}
for key, expected in expected_predicate.items():
    if predicate.get(key) != expected:
        raise SystemExit(f"signed canonical predicate field mismatch: {key}")
if provenance["canonicalIdentity"] != f"{run_id}:{run_attempt}:{predicate_sha}:{bundle_sha}":
    raise SystemExit("canonical invocation identity mismatch")
subjects = statement.get("subject")
if not isinstance(subjects, list):
    raise SystemExit("canonical attestation has no subject set")
subject_map = {}
for subject in subjects:
    if not isinstance(subject, dict): raise SystemExit("invalid canonical attestation subject")
    name, digest = subject.get("name"), subject.get("digest", {}).get("sha256")
    if not isinstance(name, str) or re.fullmatch(r"[0-9a-f]{64}", str(digest)) is None or name in subject_map:
        raise SystemExit("invalid or duplicate canonical attestation subject")
    subject_map[name] = digest
assets = provenance["assets"]
if not isinstance(assets, dict) or set(subject_map) != set(assets) or predicate.get("subjects") != subject_map:
    raise SystemExit("canonical attestation complete subject set mismatch")
for name, record in assets.items():
    if not isinstance(record, dict) or set(record) != {"sha256", "size"}:
        raise SystemExit(f"release provenance asset record mismatch: {name}")
    content = (root / name).read_bytes()
    if record["sha256"] != hashlib.sha256(content).hexdigest() or record["size"] != len(content):
        raise SystemExit(f"release provenance asset mismatch: {name}")
    if subject_map[name] != record["sha256"]:
        raise SystemExit(f"canonical attestation subject digest mismatch: {name}")
PY
}
