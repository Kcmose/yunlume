#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly PROJECT_DIR
TEST_WORK_DIR="$(mktemp -d -t yunlume-version-transition-test.XXXXXXXX)"
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

INSTALL_DIR="${TEST_WORK_DIR}/deployment"
mkdir -p "${INSTALL_DIR}"
write_legacy_manifest() {
  local version="$1"
  cat >"${INSTALL_DIR}/release-manifest.json" <<JSON
{
  "version": "${version}",
  "docker": {
    "compose": "yunlume-compose.yml",
    "composeSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "backendImage": "ghcr.io/kcmose/yunlume-backend:${version}",
    "frontendImage": "ghcr.io/kcmose/yunlume-frontend:${version}"
  },
  "host": {
    "archive": "yunlume-host-v${version}.tar.gz",
    "archiveSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
JSON
}
printf '%s\n' '1.0.6' >"${INSTALL_DIR}/VERSION"
write_legacy_manifest 1.0.6
VERSION='1.0.7'
VERSION_EXPLICIT='false'
MANIFEST_COMPATIBILITY_EPOCH='1'

if ! output="$(check_version_transition 2>&1)"; then
  printf 'A latest-channel upgrade was rejected without --version:\n%s\n' "${output}" >&2
  exit 1
fi
if [[ "${output}" != *'将 yunlume 从 1.0.6 升级到 1.0.7。'* ]]; then
  printf 'Latest-channel upgrade did not report the expected transition:\n%s\n' "${output}" >&2
  exit 1
fi

rm -f -- "${INSTALL_DIR}/VERSION"
printf '%s\n' 2 >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if (check_version_transition >"${TEST_WORK_DIR}/missing-version.out" 2>&1); then
  printf 'A deployment marker without VERSION bypassed compatibility checks.\n' >&2
  exit 1
fi
grep -Fq '当前部署缺少 VERSION' "${TEST_WORK_DIR}/missing-version.out"
rm -f -- "${INSTALL_DIR}/COMPATIBILITY_EPOCH"

printf '%s\n' '1.0.6' >"${INSTALL_DIR}/VERSION"
printf '%s\n' '{"version":"1.0.6"}' >"${INSTALL_DIR}/release-manifest.json"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if (check_version_transition >"${TEST_WORK_DIR}/incomplete-legacy.out" 2>&1); then
  printf 'An incomplete legacy manifest was accepted for automatic migration.\n' >&2
  exit 1
fi
grep -Fq '已安装发行清单格式无效' "${TEST_WORK_DIR}/incomplete-legacy.out"

printf '%s\n' '1.0.5' >"${INSTALL_DIR}/VERSION"
write_legacy_manifest 1.0.5
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if (check_version_transition >"${TEST_WORK_DIR}/unsupported-legacy.out" 2>&1); then
  printf 'An unsupported legacy deployment was assigned epoch 1.\n' >&2
  exit 1
fi
grep -Fq '旧版部署缺少兼容代际且不支持自动迁移' "${TEST_WORK_DIR}/unsupported-legacy.out"

printf '%s\n' '1.0.6' >"${INSTALL_DIR}/VERSION"
write_legacy_manifest 1.0.5
if (check_version_transition >"${TEST_WORK_DIR}/legacy-mismatch.out" 2>&1); then
  printf 'A legacy deployment with mismatched VERSION and manifest was accepted.\n' >&2
  exit 1
fi
grep -Fq '旧版部署的 VERSION 与发行清单不一致' "${TEST_WORK_DIR}/legacy-mismatch.out"
write_legacy_manifest 1.0.6

printf '%s\n' '1.0.8' >"${INSTALL_DIR}/VERSION"
printf '%s\n' '1' >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if ! output="$(check_version_transition 2>&1)"; then
  printf 'A same-epoch active downgrade was rejected:\n%s\n' "${output}" >&2
  exit 1
fi
if [[ "${output}" != *'将 yunlume 从 1.0.8 降级到 1.0.7。'* ]]; then
  printf 'Same-epoch downgrade did not report the expected transition:\n%s\n' "${output}" >&2
  exit 1
fi

cat >"${TEST_WORK_DIR}/release-manifest.json" <<'JSON'
{
  "version": "1.0.7",
  "compatibilityEpoch": 3,
  "docker": {
    "compose": "yunlume-compose.yml",
    "composeSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "backendImage": "ghcr.io/kcmose/yunlume-backend:1.0.7",
    "frontendImage": "ghcr.io/kcmose/yunlume-frontend:1.0.7"
  },
  "host": {
    "archive": "yunlume-host-v1.0.7.tar.gz",
    "archiveSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
JSON
WORK_DIR="${TEST_WORK_DIR}"
MANIFEST_FILE="${TEST_WORK_DIR}/release-manifest.json"
MANIFEST_COMPATIBILITY_EPOCH=''
parse_manifest
if [[ "${MANIFEST_COMPATIBILITY_EPOCH}" != '3' ]]; then
  printf 'Release manifest compatibilityEpoch was not parsed; got %q.\n' \
    "${MANIFEST_COMPATIBILITY_EPOCH}" >&2
  exit 1
fi

python3 - "${TEST_WORK_DIR}/release-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
data["compatibilityEpoch"] = 18446744073709551617
path.write_text(json.dumps(data), encoding="utf-8")
PY
MANIFEST_FILE="${TEST_WORK_DIR}/release-manifest.json"
if (parse_manifest >"${TEST_WORK_DIR}/huge-manifest-epoch.out" 2>&1); then
  printf 'A release manifest with an unsafe large epoch was accepted.\n' >&2
  exit 1
fi
grep -Fq '发行清单格式无效' "${TEST_WORK_DIR}/huge-manifest-epoch.out"
python3 - "${TEST_WORK_DIR}/release-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
data["compatibilityEpoch"] = 3
path.write_text(json.dumps(data), encoding="utf-8")
PY

printf '%s\n' '1' >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
MANIFEST_COMPATIBILITY_EPOCH='2'
commit_compatibility_epoch
if [[ "$(<"${INSTALL_DIR}/COMPATIBILITY_EPOCH")" != '2' ]]; then
  printf 'Successful generation-raising transition did not persist epoch 2.\n' >&2
  exit 1
fi
MANIFEST_COMPATIBILITY_EPOCH='1'
commit_compatibility_epoch
if [[ "$(<"${INSTALL_DIR}/COMPATIBILITY_EPOCH")" != '2' ]]; then
  printf 'Deployment compatibility epoch was lowered by an older target.\n' >&2
  exit 1
fi

rm -f -- "${INSTALL_DIR}/COMPATIBILITY_EPOCH"
printf '%s\n' '1.0.7' >"${INSTALL_DIR}/VERSION"
cp -- "${TEST_WORK_DIR}/release-manifest.json" "${INSTALL_DIR}/release-manifest.json"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='3'
if (check_version_transition >"${TEST_WORK_DIR}/missing-epoch.out" 2>&1); then
  printf 'A modern deployment with a missing compatibility marker was accepted.\n' >&2
  exit 1
fi
if ! grep -Fq '当前部署缺少兼容代际标记' "${TEST_WORK_DIR}/missing-epoch.out"; then
  printf 'Missing compatibility marker did not produce the expected diagnostic:\n' >&2
  cat "${TEST_WORK_DIR}/missing-epoch.out" >&2
  exit 1
fi

printf '%s\n' '1.1.0' >"${INSTALL_DIR}/VERSION"
printf '%s\n' '2' >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if (check_version_transition >"${TEST_WORK_DIR}/cross-generation.out" 2>&1); then
  printf 'A cross-generation downgrade was accepted.\n' >&2
  exit 1
fi
grep -Fq '拒绝降级：当前部署兼容代际为 2' "${TEST_WORK_DIR}/cross-generation.out"

MANIFEST_COMPATIBILITY_EPOCH='3'
if (check_version_transition >"${TEST_WORK_DIR}/older-higher.out" 2>&1); then
  printf 'An older version with a higher compatibility epoch was accepted.\n' >&2
  exit 1
fi
grep -Fq '发行元数据不一致' "${TEST_WORK_DIR}/older-higher.out"

printf '%s\n' '1.0.7' >"${INSTALL_DIR}/VERSION"
VERSION='1.0.8'
MANIFEST_COMPATIBILITY_EPOCH='1'
if (check_version_transition >"${TEST_WORK_DIR}/upgrade-lower.out" 2>&1); then
  printf 'An upgrade with a lower compatibility epoch was accepted.\n' >&2
  exit 1
fi
grep -Fq '拒绝升级' "${TEST_WORK_DIR}/upgrade-lower.out"

printf '1\n2\n' >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='12'
if (check_version_transition >"${TEST_WORK_DIR}/multiline-epoch.out" 2>&1); then
  printf 'A multi-line compatibility marker was accepted.\n' >&2
  exit 1
fi
grep -Fq '当前兼容代际标记格式无效' "${TEST_WORK_DIR}/multiline-epoch.out"

python3 - "${INSTALL_DIR}/COMPATIBILITY_EPOCH" <<'PY'
import sys
from pathlib import Path
Path(sys.argv[1]).write_bytes(b"1\x00\n")
PY
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if (check_version_transition >"${TEST_WORK_DIR}/nul-epoch.out" 2>&1); then
  printf 'A NUL-containing compatibility marker was accepted.\n' >&2
  exit 1
fi
grep -Fq '当前兼容代际标记格式无效' "${TEST_WORK_DIR}/nul-epoch.out"

printf '%s\n' invalid >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='2'
if (check_version_transition >"${TEST_WORK_DIR}/invalid-epoch.out" 2>&1); then
  printf 'A malformed compatibility marker was accepted.\n' >&2
  exit 1
fi
grep -Fq '当前兼容代际标记格式无效' "${TEST_WORK_DIR}/invalid-epoch.out"

rm -f -- "${INSTALL_DIR}/COMPATIBILITY_EPOCH"
printf '%s\n' '2' >"${TEST_WORK_DIR}/external-epoch"
ln -s -- "${TEST_WORK_DIR}/external-epoch" "${INSTALL_DIR}/COMPATIBILITY_EPOCH"
if (check_version_transition >"${TEST_WORK_DIR}/symlink-epoch.out" 2>&1); then
  printf 'A symbolic-link compatibility marker was accepted.\n' >&2
  exit 1
fi
grep -Fq '兼容代际标记不能是符号链接' "${TEST_WORK_DIR}/symlink-epoch.out"

rm -f -- "${INSTALL_DIR}/COMPATIBILITY_EPOCH"
printf '%s\n' '2' >"${INSTALL_DIR}/COMPATIBILITY_EPOCH"
if ! check_version_transition; then
  printf 'A fixed same-version reinstall with the same epoch was rejected.\n' >&2
  exit 1
fi

rm -rf -- "${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}"
printf '%s\n' docker >"${INSTALL_DIR}/.install-mode"
VERSION='1.0.7'
MANIFEST_COMPATIBILITY_EPOCH='1'
if ! check_version_transition; then
  printf 'A retry after a failed fresh install was rejected solely because its mode marker remained.\n' >&2
  exit 1
fi

printf 'Latest-channel upgrades do not require a redundant --version flag.\n'
printf 'Same-epoch active downgrades are allowed.\n'
printf 'Release manifests carry a validated compatibility epoch.\n'
printf 'The highest successfully applied compatibility epoch is persisted.\n'
printf 'Modern deployments fail closed when their compatibility marker is missing.\n'
printf 'Only the exact legacy 1.0.6 deployment migrates to epoch 1.\n'
printf 'Compatibility epochs outside the supported range fail closed.\n'
printf 'Cross-generation and inconsistent transitions fail closed.\n'
printf 'Malformed and symbolic-link compatibility markers fail closed.\n'
printf 'Fixed same-version reinstalls remain allowed.\n'
