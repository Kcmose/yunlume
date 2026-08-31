#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly PROJECT_DIR
TEST_WORK_DIR="$(mktemp -d -t yunlume-epoch-rollback-test.XXXXXXXX)"
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

mkdir -p "${TEST_WORK_DIR}/bin" "${TEST_WORK_DIR}/docker" "${TEST_WORK_DIR}/host"
cat >"${TEST_WORK_DIR}/bin/systemctl" <<'SH'
#!/usr/bin/env bash
exit 0
SH
cat >"${TEST_WORK_DIR}/bin/nginx" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod 0755 "${TEST_WORK_DIR}/bin/systemctl" "${TEST_WORK_DIR}/bin/nginx"
export PATH="${TEST_WORK_DIR}/bin:${PATH}"
WORK_DIR="${TEST_WORK_DIR}"
INSTALL_DIR="${TEST_WORK_DIR}"

DOCKER_HAD_ENV=false
DOCKER_HAD_COMPOSE=false
DOCKER_HAD_VERSION=false
DOCKER_HAD_MANIFEST=false
DOCKER_HAD_COMPATIBILITY_EPOCH=true
DOCKER_SERVICES_MUTATED=false
DOCKER_ENV_FILE="${TEST_WORK_DIR}/docker/.env"
DOCKER_COMPOSE_FILE="${TEST_WORK_DIR}/docker/compose.yml"
DOCKER_VERSION_FILE="${TEST_WORK_DIR}/docker/VERSION"
DOCKER_MANIFEST_FILE="${TEST_WORK_DIR}/docker/release-manifest.json"
DOCKER_COMPATIBILITY_EPOCH_FILE="${TEST_WORK_DIR}/docker/COMPATIBILITY_EPOCH"
DOCKER_COMPATIBILITY_EPOCH_BACKUP="${TEST_WORK_DIR}/previous.docker.COMPATIBILITY_EPOCH"
printf '%s\n' 1 >"${DOCKER_COMPATIBILITY_EPOCH_BACKUP}"
printf '%s\n' 2 >"${DOCKER_COMPATIBILITY_EPOCH_FILE}"
rollback_docker >/dev/null
[[ "$(<"${DOCKER_COMPATIBILITY_EPOCH_FILE}")" == 1 ]] || {
  printf 'Docker rollback did not restore the previous compatibility epoch.\n' >&2
  exit 1
}

HOST_HAD_CURRENT=false
HOST_HAD_NGINX_CONFIG=false
HOST_HAD_SERVICE_FILE=false
HOST_HAD_NGINX_LINK=false
HOST_HAD_VERSION=false
HOST_HAD_MANIFEST=false
HOST_HAD_APP_ENV=false
HOST_HAD_COMPATIBILITY_EPOCH=true
HOST_SERVICE_WAS_ENABLED=false
HOST_SERVICE_WAS_ACTIVE=false
HOST_NGINX_WAS_ENABLED=false
HOST_NGINX_WAS_ACTIVE=false
HOST_BACKEND_MUTATED=false
HOST_NGINX_MUTATED=false
HOST_CURRENT_LINK="${TEST_WORK_DIR}/host/current"
HOST_NGINX_CONFIG="${TEST_WORK_DIR}/host/nginx.conf"
HOST_SERVICE_FILE="${TEST_WORK_DIR}/host/service"
HOST_NGINX_LINK="${TEST_WORK_DIR}/host/nginx-link"
HOST_VERSION_FILE="${TEST_WORK_DIR}/host/VERSION"
HOST_MANIFEST_FILE="${TEST_WORK_DIR}/host/release-manifest.json"
HOST_APP_ENV_FILE="${TEST_WORK_DIR}/host/app.env"
HOST_COMPATIBILITY_EPOCH_FILE="${TEST_WORK_DIR}/host/COMPATIBILITY_EPOCH"
HOST_COMPATIBILITY_EPOCH_BACKUP="${TEST_WORK_DIR}/previous.host.COMPATIBILITY_EPOCH"
printf '%s\n' 1 >"${HOST_COMPATIBILITY_EPOCH_BACKUP}"
printf '%s\n' 2 >"${HOST_COMPATIBILITY_EPOCH_FILE}"
rollback_host >/dev/null
[[ "$(<"${HOST_COMPATIBILITY_EPOCH_FILE}")" == 1 ]] || {
  printf 'Host rollback did not restore the previous compatibility epoch.\n' >&2
  exit 1
}

printf 'Host and Docker rollback restore compatibility epoch state.\n'
