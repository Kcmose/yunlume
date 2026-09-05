#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
WORK_DIR="$(mktemp -d -t yunlume-release-manifest-test.XXXXXXXX)"
readonly WORK_DIR
trap 'rm -rf -- "${WORK_DIR}"' EXIT

python3 "${SCRIPT_DIR}/create-release-manifest.py" \
  1.2.3 kcmose \
  sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc \
  sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  yunlume-host-v1.2.3.tar.gz \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  4 \
  "${WORK_DIR}/release-manifest.json"

python3 - "${WORK_DIR}/release-manifest.json" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert manifest["version"] == "1.2.3"
assert manifest["compatibilityEpoch"] == 4
assert manifest["docker"]["backendImage"] == "ghcr.io/kcmose/yunlume-backend@sha256:" + "c" * 64
assert manifest["docker"]["frontendImage"] == "ghcr.io/kcmose/yunlume-frontend@sha256:" + "d" * 64
assert manifest["host"]["archive"] == "yunlume-host-v1.2.3.tar.gz"
PY

if python3 "${SCRIPT_DIR}/create-release-manifest.py" \
  1.2.3 kcmose \
  sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc \
  sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  yunlume-host-v1.2.3.tar.gz \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  0 \
  "${WORK_DIR}/invalid.json" >/dev/null 2>&1; then
  printf 'Manifest generator accepted compatibility epoch 0.\n' >&2
  exit 1
fi

if python3 "${SCRIPT_DIR}/create-release-manifest.py" \
  1.2.3 kcmose \
  sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc \
  sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  yunlume-host-v1.2.3.tar.gz \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  1000000000 \
  "${WORK_DIR}/invalid-large.json" >/dev/null 2>&1; then
  printf 'Manifest generator accepted an out-of-range compatibility epoch.\n' >&2
  exit 1
fi

workflow_source="$(<"${SCRIPT_DIR}/../.github/workflows/publish-images.yml")"
for expected in \
  'name: Resolve candidate digests from durable GitHub attestations' \
  'packages: write' \
  'BACKEND_DIGEST: ${{ steps.image-digests.outputs.backend }}' \
  'FRONTEND_DIGEST: ${{ steps.image-digests.outputs.frontend }}' \
  '"$VERSION" "$OWNER" "$BACKEND_DIGEST" "$FRONTEND_DIGEST"'; do
  [[ "${workflow_source}" == *"${expected}"* ]] || {
    printf 'Release workflow does not pin image content: %s\n' "${expected}" >&2
    exit 1
  }
done
if [[ "${workflow_source}" == *'{{json .Manifest.Digest}}'* ]]; then
  printf 'Release workflow captures quoted JSON instead of a bare digest.\n' >&2
  exit 1
fi
if [[ "${workflow_source}" != *"--format '{{.Manifest.Digest}}'"* ]]; then
  printf 'Release workflow does not read a bare immutable digest.\n' >&2
  exit 1
fi

printf 'Release manifest generation pins validated immutable image digests.\n'
