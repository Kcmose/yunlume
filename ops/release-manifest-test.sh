#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
WORK_DIR="$(mktemp -d -t yunlume-release-manifest-test.XXXXXXXX)"
readonly WORK_DIR
trap 'rm -rf -- "${WORK_DIR}"' EXIT

python3 "${SCRIPT_DIR}/create-release-manifest.py" \
  1.2.3 kcmose \
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
assert manifest["docker"]["backendImage"] == "ghcr.io/kcmose/yunlume-backend:1.2.3"
assert manifest["docker"]["frontendImage"] == "ghcr.io/kcmose/yunlume-frontend:1.2.3"
assert manifest["host"]["archive"] == "yunlume-host-v1.2.3.tar.gz"
PY

if python3 "${SCRIPT_DIR}/create-release-manifest.py" \
  1.2.3 kcmose \
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
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  yunlume-host-v1.2.3.tar.gz \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  1000000000 \
  "${WORK_DIR}/invalid-large.json" >/dev/null 2>&1; then
  printf 'Manifest generator accepted an out-of-range compatibility epoch.\n' >&2
  exit 1
fi

printf 'Release manifest generation includes a validated compatibility epoch.\n'
