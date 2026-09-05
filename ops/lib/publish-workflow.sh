#!/usr/bin/env bash
# 发布边界共用的只读校验；每个失败都显式返回，不依赖调用者的 errexit。

resolve_release_tag_commit() {
  local repository="$1" tag="$2" row type sha extra depth
  row="$(gh api "repos/$repository/git/ref/tags/$tag" --jq '[.object.type, .object.sha] | @tsv')" || return
  for ((depth = 0; depth <= 16; depth++)); do
    [[ "$row" != *$'\n'* ]] || { printf 'Ambiguous tag object response.\n' >&2; return 1; }
    IFS=$'\t' read -r type sha extra <<< "$row"
    [[ "$sha" =~ ^[0-9a-f]{40}$ && -z "$extra" ]] || return 1
    if [[ "$type" == commit ]]; then printf '%s\n' "$sha"; return; fi
    [[ "$type" == tag && "$depth" -lt 16 ]] || {
      printf 'Tag does not resolve to a commit within 16 annotated tags.\n' >&2; return 1;
    }
    row="$(gh api "repos/$repository/git/tags/$sha" --jq '[.object.type, .object.sha] | @tsv')" || return
  done
}

assert_release_tag_commit() {
  local actual
  actual="$(resolve_release_tag_commit "$1" "$2")" || return
  [[ "$actual" == "$3" ]] || {
    printf 'Remote tag %s moved: expected %s, found %s.\n' "$2" "$3" "$actual" >&2; return 1;
  }
}

verify_release_checksums() {
  local directory="$1" archive="$2"
  python3 - "$directory" "$archive" <<'PY'
import hashlib, re, sys
from pathlib import Path
root, archive = Path(sys.argv[1]), sys.argv[2]
expected = {"install.sh", "release-manifest.json", "yunlume-compose.yml", archive, archive + ".sha256"}
seen = set()
for line in (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
    match = re.fullmatch(r"([0-9a-f]{64}) [ *]([A-Za-z0-9._-]+)", line)
    if not match or match[2] not in expected or match[2] in seen:
        raise SystemExit("SHA256SUMS has malformed, duplicate, or unexpected members")
    digest, name = match.groups()
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
        raise SystemExit(f"SHA256SUMS digest mismatch: {name}")
    seen.add(name)
if seen != expected:
    raise SystemExit("SHA256SUMS does not cover exactly the expected assets")
PY
}

verify_rolling_aliases() {
  local owner="$1" version="$2" backend="$3" frontend="$4" component expected actual image
  [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || return 1
  for component in backend frontend; do
    if [[ "$component" == backend ]]; then expected="$backend"; else expected="$frontend"; fi
    [[ "$expected" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
    image="ghcr.io/${owner}/yunlume-${component}:${version%.*}"
    actual="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' "$image")" || {
      printf 'Published rolling alias is missing or unreadable: %s. No mutation attempted.\n' "$image" >&2; return 1;
    }
    [[ "$actual" == "$expected" ]] || {
      printf 'Published rolling alias mismatch: %s expected %s, found %s. No mutation attempted.\n' "$image" "$expected" "$actual" >&2
      return 1
    }
  done
}

write_backend_jar_producer() {
  python3 - "$1" <<'PY'
import json, os, sys
from pathlib import Path
value = {"schemaVersion": 1, "artifactId": os.environ["PRODUCER_ARTIFACT_ID"],
         "artifactDigest": os.environ["PRODUCER_ARTIFACT_DIGEST"], "runId": os.environ["PRODUCER_RUN_ID"],
         "runAttempt": os.environ["PRODUCER_RUN_ATTEMPT"], "sourceSha": os.environ["GITHUB_SHA"],
         "jarSha256": os.environ["PRODUCER_JAR_SHA"]}
Path(sys.argv[1]).write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY
}

verify_backend_jar_producer() {
  local descriptor="$1" repository="$2" sha="$3" values artifact run attempt temporary status
  values="$(python3 ops/validate-artifact-producer.py "$descriptor" "$sha")" || return
  IFS=$'\t' read -r artifact run attempt <<< "$values"
  temporary="$(mktemp -d)" || return
  # Artifact API 无 run_attempt 字段；同时核对其唯一名称和该 attempt 的成功生产 job/创建时间。
  if gh api "repos/$repository/actions/artifacts/$artifact" > "$temporary/artifact.json" &&
     gh api "repos/$repository/actions/runs/$run/attempts/$attempt" > "$temporary/attempt.json" &&
     gh api --paginate --slurp "repos/$repository/actions/runs/$run/attempts/$attempt/jobs" > "$temporary/jobs.json" &&
     python3 ops/validate-artifact-producer.py "$descriptor" "$sha" "$temporary" "$repository"; then
    status=0
  else status=$?; fi
  rm -rf -- "$temporary"
  return "$status"
}

emit_backend_jar_producer() {
  python3 - "$1" <<'PY'
import json, sys
v = json.load(open(sys.argv[1], encoding="utf-8"))
for key, name in (("artifactId", "artifact_id"), ("artifactDigest", "artifact_digest"),
                  ("runId", "run_id"), ("runAttempt", "run_attempt"), ("jarSha256", "jar_sha256")):
    print(f"{name}={v[key]}")
PY
}

verify_attested_backend_jar() {
  local descriptor="$1" directory="$2" repository="$3" ref="$4" sha="$5" expected
  expected="$(jq -er '.jarSha256' "$descriptor")" || return
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || return 1
  python3 - "$descriptor" "$directory" <<'PY'
import hashlib, json, sys
from pathlib import Path
v = json.load(open(sys.argv[1], encoding="utf-8")); root = Path(sys.argv[2])
lines = (root / "identity.txt").read_text(encoding="utf-8").splitlines()
expected = ["sha256=" + v["jarSha256"], "source_sha=" + v["sourceSha"],
            "run_id=" + v["runId"], "run_attempt=" + v["runAttempt"]]
if len(lines) != 5 or lines[:4] != expected or not lines[4].startswith("attestation_id=") or lines[4] == "attestation_id=":
    raise SystemExit("Attested JAR producer identity mismatch")
if hashlib.sha256((root / "app.jar").read_bytes()).hexdigest() != v["jarSha256"]:
    raise SystemExit("Attested JAR SHA mismatch")
PY
  [[ $? == 0 ]] || return 1
  gh attestation verify "$directory/app.jar" --bundle "$directory/attestation.sigstore.json" \
    --repo "$repository" --signer-workflow "$repository/.github/workflows/publish-images.yml" \
    --source-ref "$ref" --source-digest "$sha" >/dev/null
}

resolve_canonical_owner() {
  local repository="$1" release_id="$2" sha="$3" run="$4" attempt="$5" owner_id temporary identity
  owner_id="$(list_release_asset_ids "$repository" "$release_id" canonical-owner.json)" || return
  [[ "$owner_id" != *$'\n'* ]] || return 1
  if [[ -z "$owner_id" ]]; then
    [[ "$run" =~ ^[1-9][0-9]*$ && "$attempt" =~ ^[1-9][0-9]*$ ]] || return 1
    printf 'canonical_current=true\ncanonical_run_id=%s\ncanonical_run_attempt=%s\n' "$run" "$attempt"
    return
  fi
  temporary="$(mktemp)" || return
  if ! gh api "repos/$repository/releases/assets/$owner_id" -H 'Accept: application/octet-stream' > "$temporary"; then
    rm -f -- "$temporary"; return 1
  fi
  identity="$(jq -er --arg sha "$sha" \
    'select(type == "object" and keys == ["runAttempt","runId","schemaVersion","sourceSha"] and .schemaVersion == 1 and .sourceSha == $sha and (.runId|test("^[1-9][0-9]*$")) and (.runAttempt|test("^[1-9][0-9]*$"))) | [.runId,.runAttempt] | @tsv' "$temporary")" || {
    rm -f -- "$temporary"; return 1;
  }
  rm -f -- "$temporary"
  IFS=$'\t' read -r run attempt <<< "$identity"
  printf 'canonical_current=false\ncanonical_run_id=%s\ncanonical_run_attempt=%s\n' "$run" "$attempt"
}
