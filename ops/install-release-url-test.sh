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
release_base_url = "https://github.com/Kcmose/yunlume/releases"
source = source_path.read_text(encoding="utf-8")
if source.count(placeholder) != 1:
    raise SystemExit("install.sh must contain exactly one release URL placeholder")
rendered_path.write_text(source.replace(placeholder, release_base_url), encoding="utf-8")
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
  "${WORK_DIR}/install.sh" --version 1.0.0 \
  >"${WORK_DIR}/stdout" 2>"${WORK_DIR}/stderr"
status=$?
set -e

if (( status == 0 )); then
  printf 'Rendered Release installer unexpectedly completed successfully.\n' >&2
  exit 1
fi
if grep -Fq '当前源码安装脚本尚未写入发布地址' "${WORK_DIR}/stderr"; then
  printf 'Rendered Release installer rejected its embedded release URL.\n' >&2
  exit 1
fi
if ! grep -Fq '安装器仅支持 Linux' "${WORK_DIR}/stderr"; then
  printf 'Rendered Release installer did not pass URL validation. stderr:\n' >&2
  cat "${WORK_DIR}/stderr" >&2
  exit 1
fi

printf 'Rendered Release installer accepts its embedded release URL.\n'
