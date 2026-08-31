#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly PROJECT_DIR
AWK_BIN="${AWK_BIN:-awk}"
readonly AWK_BIN
AWK_PATH="$(command -v -- "${AWK_BIN}")" || {
  printf 'Requested awk implementation is unavailable: %s\n' "${AWK_BIN}" >&2
  exit 1
}
readonly AWK_PATH
TEST_WORK_DIR="$(mktemp -d -t yunlume-install-runtime-config-test.XXXXXXXX)"
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

mkdir -p "${TEST_WORK_DIR}/bin"
ln -s -- "${AWK_PATH}" "${TEST_WORK_DIR}/bin/awk"
cat >"${TEST_WORK_DIR}/bin/ip" <<'SH'
#!/usr/bin/env bash
case "${1:-}" in
  -4)
    printf '%s\n' '1.1.1.1 via 192.0.2.1 dev eth0 src 198.51.100.42 uid 0'
    ;;
  -6)
    exit 1
    ;;
  *)
    exit 2
    ;;
esac
SH
chmod 0755 "${TEST_WORK_DIR}/bin/ip"
export PATH="${TEST_WORK_DIR}/bin:${PATH}"

is_global_ip_address() {
  [[ "${1:-}" == "198.51.100.42" ]]
}

set +e
detected_host="$(detect_public_access_host \
  2>"${TEST_WORK_DIR}/detect-public-host.stderr")"
detect_status=$?
set -e
if (( detect_status != 0 )) || [[ "${detected_host}" != "198.51.100.42" ]]; then
  printf 'Public address detection is not portable across awk implementations. stderr:\n' >&2
  cat "${TEST_WORK_DIR}/detect-public-host.stderr" >&2
  exit 1
fi

export APP_PORT=18080
write_docker_env "${TEST_WORK_DIR}/docker.env" backend-image frontend-image
if ! grep -Fxq \
  'CORS_ALLOWED_ORIGINS=http://localhost:18080,http://127.0.0.1:18080' \
  "${TEST_WORK_DIR}/docker.env"; then
  printf 'Docker installer did not configure backend CORS origins for its selected port.\n' >&2
  exit 1
fi

printf '%s\n' \
  'CORS_ALLOWED_ORIGINS=http://localhost:8080,http://127.0.0.1:8080,https://nav.example.com' \
  >"${TEST_WORK_DIR}/existing.env"
configure_local_cors_origins "${TEST_WORK_DIR}/existing.env"
if ! grep -Fxq \
  'CORS_ALLOWED_ORIGINS=http://localhost:18080,http://127.0.0.1:18080,https://nav.example.com' \
  "${TEST_WORK_DIR}/existing.env"; then
  printf 'Installer upgrade did not refresh local CORS origins while preserving custom origins.\n' >&2
  exit 1
fi

python3 - \
  "${PROJECT_DIR}/nav-frontend/nginx/nginx.conf.template" \
  "${PROJECT_DIR}/deploy/host/yunlume.nginx.conf" <<'PY'
import re
import sys
from pathlib import Path

for name in sys.argv[1:]:
    path = Path(name)
    values = re.findall(r"proxy_set_header\s+Host\s+([^;]+);", path.read_text(encoding="utf-8"))
    if not values:
        raise SystemExit(f"{path} has no proxied Host header")
    invalid = [value for value in values if value.strip() != "$http_host"]
    if invalid:
        raise SystemExit(
            f"{path} must preserve the browser authority for same-origin CORS checks; found {invalid}"
        )
PY

printf 'Public address detection works with %s.\n' "${AWK_BIN}"
printf 'Installer and reverse proxies preserve same-origin backend CORS behavior.\n'
