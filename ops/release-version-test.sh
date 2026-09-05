#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR="${SCRIPT_DIR}/validate-release-version.py"

python3 "${VALIDATOR}" v1.3.0 v1.0.10 v1.2.9 draft main

if python3 "${VALIDATOR}" v1.2.9 v1.2.9 >/dev/null 2>&1; then
  printf 'Equal release version was accepted.\n' >&2
  exit 1
fi

if python3 "${VALIDATOR}" v1.2.8 v1.2.9 >/dev/null 2>&1; then
  printf 'Older release version was accepted.\n' >&2
  exit 1
fi

if python3 "${VALIDATOR}" v01.2.3 v1.0.0 >/dev/null 2>&1; then
  printf 'Malformed release version was accepted.\n' >&2
  exit 1
fi

workflow_source="$(<"${SCRIPT_DIR}/../.github/workflows/publish-images.yml")"
for expected in \
  'gh api -H '\''X-GitHub-Api-Version: 2026-03-10'\'' --paginate "repos/$REPOSITORY/releases"' \
  'python3 ops/validate-release-version.py "$RELEASE_TAG"' \
  'ops/release-version-test.sh' \
  'ops/publish-workflow-test.sh' \
  'ops/rollback-release-test.sh' \
  "github.ref_type == 'tag' && 'release' || github.ref" \
  'select(.draft == false and .tag_name !=' \
  'reservation_marker="<!-- yunlume-release-reservation-v3:${GITHUB_SHA} -->"'; do
  if [[ "${workflow_source}" != *"${expected}"* ]]; then
    printf 'Release workflow is missing monotonic-version protection: %s\n' "${expected}" >&2
    exit 1
  fi
done

printf 'Release versions must increase monotonically.\n'
