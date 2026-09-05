#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t yunlume-host-proxy-test.XXXXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT
[[ "$(id -u)" == 0 ]] || { printf 'This permission test must run as root.\n' >&2; exit 1; }
python3 - "${PROJECT_DIR}/install.sh" "${TEST_ROOT}/install-lib.sh" <<'PY'
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text(encoding="utf-8")
entry = '\nmain "$@"\n'
assert source.count(entry) == 1
Path(sys.argv[2]).write_text(source.replace(entry, "\n"), encoding="utf-8")
PY
source "${TEST_ROOT}/install-lib.sh"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT
INSTALL_DIR="${TEST_ROOT}/application"
WORK_DIR="${TEST_ROOT}/work"
JAVA_BIN=/usr/bin/java
APP_PORT=18080
MODE=host
mkdir -p "${WORK_DIR}"
env_file="${TEST_ROOT}/app.env"
template="${PROJECT_DIR}/deploy/host/yunlume.nginx.conf"

render_template "${PROJECT_DIR}/deploy/host/app.env.template" "${env_file}" fixture-only-secret
chmod 0600 "${env_file}"
render_host_nginx_config "${template}" "${TEST_ROOT}/default.conf" "${env_file}"
[[ "${HOST_TRUST_PROXY_HEADERS}" == false && "${HOST_BIND_ADDRESS}" == 0.0.0.0 ]]
grep -Fq 'set_real_ip_from unix:;' "${TEST_ROOT}/default.conf"
[[ "$(read_host_listener "${TEST_ROOT}/default.conf")" == $'0.0.0.0\n18080' ]]

upsert_env "${env_file}" HOST_BIND_ADDRESS 127.0.0.1
upsert_env "${env_file}" HOST_TRUST_PROXY_HEADERS true
upsert_env "${env_file}" HOST_TRUSTED_PROXY_CIDR 127.0.0.2/32
render_host_nginx_config "${template}" "${TEST_ROOT}/trusted.conf" "${env_file}"
[[ "${HOST_BIND_ADDRESS}" == 127.0.0.1 && "${HOST_TRUSTED_PROXY_CIDR}" == 127.0.0.2/32 ]]
grep -Fq 'set_real_ip_from 127.0.0.2/32;' "${TEST_ROOT}/trusted.conf"
[[ "$(read_host_listener "${TEST_ROOT}/trusted.conf")" == $'127.0.0.1\n18080' ]]
[[ "$(print_access_url)" == *'http://127.0.0.1:18080/healthz'* ]]

# 执行升级实际使用的环境更新，再从保留的同一文件生成受管路由。
before="$(grep '^HOST_' "${env_file}")"
APP_PORT=19090
upsert_env "${env_file}" NAV_ALLOW_INSECURE_DATABASE_SETUP false
upsert_env "${env_file}" NAV_TRUST_FORWARDED_HTTPS true
upsert_env "${env_file}" NAV_TRUSTED_PROXY_PEERS '127.0.0.1,::1'
configure_local_cors_origins "${env_file}"
render_host_nginx_config "${template}" "${TEST_ROOT}/upgraded.conf" "${env_file}"
[[ "$(grep '^HOST_' "${env_file}")" == "${before}" ]]
[[ "$(read_host_listener "${TEST_ROOT}/upgraded.conf")" == $'127.0.0.1\n19090' ]]
[[ "$(docker_probe_base_url "${HOST_BIND_ADDRESS}" "${APP_PORT}")" == http://127.0.0.1:19090 ]]

upsert_env "${env_file}" HOST_BIND_ADDRESS ::1
upsert_env "${env_file}" HOST_TRUSTED_PROXY_CIDR ::1/128
render_host_nginx_config "${template}" "${TEST_ROOT}/ipv6.conf" "${env_file}"
[[ "$(read_host_listener "${TEST_ROOT}/ipv6.conf")" == $'::1\n19090' ]]
[[ "$(docker_probe_base_url "${HOST_BIND_ADDRESS}" "${APP_PORT}")" == 'http://[::1]:19090' ]]

cp "${env_file}" "${TEST_ROOT}/valid.env"
rejected=0
reject_config() {
  local key="$1" value="$2"
  cp "${TEST_ROOT}/valid.env" "${env_file}"
  upsert_env "${env_file}" "${key}" "${value}"
  if load_host_proxy_config "${env_file}" >"${TEST_ROOT}/rejected.out" 2>&1; then
    printf 'Unsafe proxy config accepted: %s\n' "${key}" >&2
    exit 1
  fi
  rejected=$((rejected + 1))
}
reject_config HOST_BIND_ADDRESS 0.0.0.0
reject_config HOST_BIND_ADDRESS ::
reject_config HOST_BIND_ADDRESS 8.8.8.8
reject_config HOST_BIND_ADDRESS 169.254.1.2
reject_config HOST_BIND_ADDRESS 224.0.0.1
reject_config HOST_BIND_ADDRESS 'fe80::1%eth0'
reject_config HOST_BIND_ADDRESS '127.0.0.1;return 200;'
reject_config HOST_TRUSTED_PROXY_CIDR 0.0.0.0/0
reject_config HOST_TRUSTED_PROXY_CIDR ::/0
reject_config HOST_TRUSTED_PROXY_CIDR 127.0.0.1/99
reject_config HOST_TRUSTED_PROXY_CIDR '127.0.0.1/32;include /tmp/injected;'
reject_config HOST_TRUST_PROXY_HEADERS TRUE
cp "${TEST_ROOT}/valid.env" "${env_file}"
printf 'HOST_BIND_ADDRESS=127.0.0.1\n' >>"${env_file}"
if load_host_proxy_config "${env_file}" >/dev/null 2>&1; then exit 1; fi
cp "${TEST_ROOT}/valid.env" "${env_file}"
chmod 0666 "${env_file}"
if load_host_proxy_config "${env_file}" >/dev/null 2>&1; then exit 1; fi
chmod 0600 "${env_file}"
ln -s "${env_file}" "${TEST_ROOT}/symlink.env"
if load_host_proxy_config "${TEST_ROOT}/symlink.env" >/dev/null 2>&1; then exit 1; fi
printf 'server {\n  listen 443 ssl;\n}\n' >"${TEST_ROOT}/manual-tls.conf"
if read_host_listener "${TEST_ROOT}/manual-tls.conf" >/dev/null 2>&1; then exit 1; fi

python3 - "${TEST_ROOT}/trusted.conf" <<'PY'
import re
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text(encoding="utf-8")
assert "__HOST_" not in source
protocols = re.findall(r"proxy_set_header\s+X-Forwarded-Proto\s+([^;]+);", source)
assert len(protocols) == 12 and set(protocols) == {"$yunlume_forwarded_proto"}
assert 'geo $realip_remote_addr $yunlume_proxy_peer_trusted' in source
PY
printf 'Host proxy config: defaults, trusted/IPv6, upgrade preservation and %s unsafe values plus permission/symlink guards passed.\n' "${rejected}"
