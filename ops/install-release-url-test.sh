#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly PROJECT_DIR

WORK_DIR="$(mktemp -d -t yunlume-install-release-url-test.XXXXXXXX)"
trap 'rm -rf -- "${WORK_DIR}"' EXIT

python3 - "${PROJECT_DIR}/install.sh" "${WORK_DIR}/install.sh" <<'PY'
import sys
from pathlib import Path

source_path = Path(sys.argv[1])
rendered_path = Path(sys.argv[2])
placeholder = "__YUNLUME_RELEASE_BASE_URL__"
version_placeholder = "__YUNLUME_RELEASE_VERSION__"
release_base_url = "https://github.com/Kcmose/yunlume/releases"
release_version = "1.0.0"
source = source_path.read_text(encoding="utf-8")
if source.count(placeholder) != 1:
    raise SystemExit("install.sh must contain exactly one release URL placeholder")
if source.count(version_placeholder) != 1:
    raise SystemExit("install.sh must contain exactly one release version placeholder")
rendered_path.write_text(
    source.replace(placeholder, release_base_url).replace(version_placeholder, release_version),
    encoding="utf-8",
)
PY
chmod 0755 "${WORK_DIR}/install.sh"

mkdir -p "${WORK_DIR}/bin"
cat >"${WORK_DIR}/bin/uname" <<'SH'
#!/usr/bin/env bash
case "${1:-}" in
  -m) printf '%s\n' x86_64 ;;
  -s) printf '%s\n' Darwin ;;
  *) exec /usr/bin/uname "$@" ;;
esac
SH
chmod 0755 "${WORK_DIR}/bin/uname"

set +e
PATH="${WORK_DIR}/bin:${PATH}" \
  "${PROJECT_DIR}/install.sh" --version 1.0.0 \
  >"${WORK_DIR}/source-stdout" 2>"${WORK_DIR}/source-stderr"
source_status=$?
set -e

if (( source_status == 0 )); then
  printf 'Source installer unexpectedly accepted its unresolved release URL placeholder.\n' >&2
  exit 1
fi
if ! grep -Fq '当前源码安装脚本尚未写入发布地址' "${WORK_DIR}/source-stderr"; then
  printf 'Source installer did not reject its unresolved release URL placeholder. stderr:\n' >&2
  cat "${WORK_DIR}/source-stderr" >&2
  exit 1
fi

set +e
PATH="${WORK_DIR}/bin:${PATH}" \
  "${WORK_DIR}/install.sh" --version 1.0.0 \
  >"${WORK_DIR}/rendered-stdout" 2>"${WORK_DIR}/rendered-stderr"
rendered_status=$?
set -e

if (( rendered_status == 0 )); then
  printf 'Rendered Release installer unexpectedly completed successfully.\n' >&2
  exit 1
fi
if grep -Fq '当前源码安装脚本尚未写入发布地址' "${WORK_DIR}/rendered-stderr"; then
  printf 'Rendered Release installer rejected its embedded release URL.\n' >&2
  exit 1
fi
if grep -Fq '__YUNLUME_RELEASE_VERSION__' "${WORK_DIR}/install.sh"; then
  printf 'Rendered Release installer still contains its version placeholder.\n' >&2
  exit 1
fi
if ! grep -Fq '安装器仅支持 Linux' "${WORK_DIR}/rendered-stderr"; then
  printf 'Rendered Release installer did not pass URL validation. stderr:\n' >&2
  cat "${WORK_DIR}/rendered-stderr" >&2
  exit 1
fi

set +e
PATH="${WORK_DIR}/bin:${PATH}" \
  "${WORK_DIR}/install.sh" --version 1.0.1 \
  >"${WORK_DIR}/mismatch-stdout" 2>"${WORK_DIR}/mismatch-stderr"
mismatch_status=$?
set -e
if (( mismatch_status == 0 )); then
  printf 'Rendered Release installer accepted a different --version.\n' >&2
  exit 1
fi
if ! grep -Fq '此安装器固定用于版本 1.0.0，不能改为 1.0.1' "${WORK_DIR}/mismatch-stderr"; then
  printf 'Rendered Release installer did not reject a different target version. stderr:\n' >&2
  cat "${WORK_DIR}/mismatch-stderr" >&2
  exit 1
fi

set +e
PATH="${WORK_DIR}/bin:${PATH}" \
  "${WORK_DIR}/install.sh" \
  >"${WORK_DIR}/no-version-stdout" 2>"${WORK_DIR}/no-version-stderr"
no_version_status=$?
set -e
if (( no_version_status == 0 )); then
  printf 'Rendered Release installer unexpectedly completed successfully.\n' >&2
  exit 1
fi
if ! grep -Fq '安装器仅支持 Linux' "${WORK_DIR}/no-version-stderr"; then
  printf 'Rendered Release installer still required --version. stderr:\n' >&2
  cat "${WORK_DIR}/no-version-stderr" >&2
  exit 1
fi

printf 'Source installer rejects its unresolved release URL placeholder.\n'
printf 'Rendered Release installer accepts its embedded release URL and version.\n'
printf 'Rendered Release installer cannot be redirected to another version.\n'
