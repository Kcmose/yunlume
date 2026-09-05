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

APP_PORT_EXPLICIT='false'
APP_PORT="${DEFAULT_PORT}"
inherit_existing_port 18080
[[ "${APP_PORT}" == '18080' ]] || {
  printf 'Installer did not inherit an existing custom port.\n' >&2
  exit 1
}
APP_PORT_EXPLICIT='true'
APP_PORT=19090
inherit_existing_port 18080
[[ "${APP_PORT}" == '19090' ]] || {
  printf 'Installer overwrote an explicitly requested port.\n' >&2
  exit 1
}
APP_PORT=18080

assert_probe_url() {
  local bind_address="$1"
  local expected="$2"
  local actual
  actual="$(docker_probe_base_url "${bind_address}" "${APP_PORT}")"
  [[ "${actual}" == "${expected}" ]] || {
    printf 'Unexpected Docker probe URL for %s: %s\n' "${bind_address}" "${actual}" >&2
    exit 1
  }
}
assert_probe_url '0.0.0.0' 'http://127.0.0.1:18080'
assert_probe_url '192.168.50.20' 'http://192.168.50.20:18080'
assert_probe_url '::' 'http://[::1]:18080'
assert_probe_url '2001:db8::20' 'http://[2001:db8::20]:18080'

curl() {
  case "${*: -1}" in
    */healthz) return 0 ;;
    */api/health) printf '%s\n' '{"data":{"status":"INSTALLING"}}' ;;
    */api/install/status) printf '%s\n' '{"data":{"state":"INSTALLING"}}' ;;
    *) return 99 ;;
  esac
}
wait_for_http 'http://127.0.0.1:18080' 1 false || {
  printf 'Fresh install should accept the reachable installation wizard.\n' >&2
  exit 1
}
if wait_for_http 'http://127.0.0.1:18080' 1 true; then
  printf 'Upgrade health check accepted INSTALLING instead of requiring UP/COMPLETED.\n' >&2
  exit 1
fi
unset -f curl

write_docker_env "${TEST_WORK_DIR}/docker.env" backend-image frontend-image
if ! grep -Fxq 'NAV_ALLOW_INSECURE_DATABASE_SETUP=false' "${TEST_WORK_DIR}/docker.env"; then
  printf 'Docker installer did not default credential submission to HTTPS-only.\n' >&2
  exit 1
fi
if ! grep -Fxq 'NAV_TRUST_FORWARDED_HTTPS=true' "${TEST_WORK_DIR}/docker.env" ||
   ! grep -Fxq 'NAV_TRUSTED_PROXY_PEERS=frontend' "${TEST_WORK_DIR}/docker.env"; then
  printf 'Docker installer no longer trusts HTTPS from its configured frontend proxy.\n' >&2
  exit 1
fi
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

python3 - \
  "${PROJECT_DIR}/docker-compose.yml" \
  "${PROJECT_DIR}/deploy/host/app.env.template" <<'PY'
import sys
from pathlib import Path

compose = Path(sys.argv[1]).read_text(encoding="utf-8")
host = Path(sys.argv[2]).read_text(encoding="utf-8")
if "NAV_ALLOW_INSECURE_DATABASE_SETUP: ${NAV_ALLOW_INSECURE_DATABASE_SETUP:-false}" not in compose:
    raise SystemExit("Compose must default database and Redis credential submission to HTTPS-only")
if "NAV_TRUST_FORWARDED_HTTPS: ${NAV_TRUST_FORWARDED_HTTPS:-true}" not in compose:
    raise SystemExit("Compose must keep configured trusted-proxy HTTPS support enabled")
if "NAV_ALLOW_INSECURE_DATABASE_SETUP=false" not in host:
    raise SystemExit("Host template must default database and Redis credential submission to HTTPS-only")
if "NAV_TRUST_FORWARDED_HTTPS=true" not in host:
    raise SystemExit("Host template must keep loopback reverse-proxy HTTPS support enabled")
PY

python3 - "${PROJECT_DIR}/install.sh" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
required = [
    'wait_for_http "$(docker_probe_base_url "${DOCKER_BIND_ADDRESS}" "${APP_PORT}")" 90 "${EXISTING_MANAGED_DEPLOYMENT}"',
    'wait_for_http "http://127.0.0.1:${APP_PORT}" 90 "${EXISTING_MANAGED_DEPLOYMENT}"',
    'validate_managed_runtime_config docker "${env_file}" "${compose_file}"',
    'validate_managed_runtime_config host "${env_file}"',
]
for text in required:
    if text not in source:
        raise SystemExit(
            f"Installer upgrade path does not request strict runtime state verification: {text}"
        )
PY

fresh_dir="${TEST_WORK_DIR}/fresh-managed-state"
INSTALL_DIR="${fresh_dir}"
MODE=docker
VERSION=1.1.0
MANIFEST_COMPATIBILITY_EPOCH=1
EXISTING_MANAGED_DEPLOYMENT=false
ensure_install_mode
check_version_transition
[[ "${EXISTING_MANAGED_DEPLOYMENT}" == false ]] || {
  printf 'Fresh install was incorrectly classified as an existing managed deployment.\n' >&2
  exit 1
}

existing_dir="${TEST_WORK_DIR}/existing-managed-state"
mkdir -p "${existing_dir}"
printf '%s\n' docker >"${existing_dir}/.install-mode"
printf '%s\n' 1.0.9 >"${existing_dir}/VERSION"
printf '%s\n' 1 >"${existing_dir}/COMPATIBILITY_EPOCH"
INSTALL_DIR="${existing_dir}"
MODE=docker
VERSION=1.1.0
MANIFEST_COMPATIBILITY_EPOCH=1
EXISTING_MANAGED_DEPLOYMENT=false
ensure_install_mode
check_version_transition
[[ "${EXISTING_MANAGED_DEPLOYMENT}" == true ]] || {
  printf 'Validated managed deployment metadata did not set the upgrade marker.\n' >&2
  exit 1
}
if (validate_managed_runtime_config docker "${existing_dir}/.env" "${existing_dir}/compose.yml") \
    >/dev/null 2>&1; then
  printf 'Existing managed Docker deployment accepted missing runtime config.\n' >&2
  exit 1
fi
if (validate_managed_runtime_config host "${existing_dir}/app.env") >/dev/null 2>&1; then
  printf 'Existing managed Host deployment accepted missing runtime config.\n' >&2
  exit 1
fi

access_text="$(print_access_url)"
[[ "${access_text}" == *'HTTP 地址仅用于连通性诊断'* &&
   "${access_text}" == *'配置受信任的 HTTPS 域名后再提交数据库、Redis 或管理员凭据'* ]] || {
  printf 'Installer still presents public HTTP as a credential-submission URL.\n' >&2
  exit 1
}

printf 'Public address detection works with %s.\n' "${AWK_BIN}"
printf 'Installer and reverse proxies preserve same-origin backend CORS behavior.\n'
