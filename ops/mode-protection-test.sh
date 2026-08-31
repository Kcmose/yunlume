#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly PROJECT_DIR
TEST_WORK_DIR="$(mktemp -d -t yunlume-mode-protection-test.XXXXXXXX)"
readonly TEST_WORK_DIR
trap 'rm -rf -- "${TEST_WORK_DIR}"' EXIT

python3 - "${PROJECT_DIR}/install.sh" "${TEST_WORK_DIR}/install-lib.sh" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
entrypoint = '\nmain "$@"\n'
if source.count(entrypoint) != 1:
    raise SystemExit("install.sh entrypoint is not uniquely identifiable")
Path(sys.argv[2]).write_text(source.replace(entrypoint, "\n"), encoding="utf-8")
PY

# shellcheck source=/dev/null
source "${TEST_WORK_DIR}/install-lib.sh"
trap 'rm -rf -- "${TEST_WORK_DIR}"' EXIT

MODE='docker'
INSTALL_DIR="${TEST_WORK_DIR}/symlink-deployment"
mkdir -p "${INSTALL_DIR}"
printf '%s\n' docker >"${TEST_WORK_DIR}/external-mode"
ln -s -- "${TEST_WORK_DIR}/external-mode" "${INSTALL_DIR}/.install-mode"
if (ensure_install_mode >"${TEST_WORK_DIR}/symlink.out" 2>&1); then
  printf 'A symbolic-link deployment mode marker was accepted.\n' >&2
  exit 1
fi
if ! grep -Fq '模式标记不能是符号链接' "${TEST_WORK_DIR}/symlink.out"; then
  printf 'Mode marker symlink did not produce the expected diagnostic:\n' >&2
  cat "${TEST_WORK_DIR}/symlink.out" >&2
  exit 1
fi

INSTALL_DIR="${TEST_WORK_DIR}/invalid-deployment"
mkdir -p "${INSTALL_DIR}"
printf '%s\n' invalid >"${INSTALL_DIR}/.install-mode"
if (ensure_install_mode >"${TEST_WORK_DIR}/invalid.out" 2>&1); then
  printf 'An invalid deployment mode marker was accepted.\n' >&2
  exit 1
fi
if ! grep -Fq '当前部署模式标记格式无效' "${TEST_WORK_DIR}/invalid.out"; then
  printf 'Invalid mode marker did not produce the expected diagnostic:\n' >&2
  cat "${TEST_WORK_DIR}/invalid.out" >&2
  exit 1
fi

printf 'Deployment mode markers reject symlinks and invalid values.\n'
