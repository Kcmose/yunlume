#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

# Destructive scope: only resources carrying this run's unique ownership label.
# This script never calls `docker compose down`, `prune`, `remove-orphans`, or
# any command that selects resources by a broad production project name.

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly LABEL_KEY="io.yunlume.install-e2e.run"
readonly STATE_ROOT="${E2E_STATE_ROOT:-/var/tmp/yunlume-install-e2e-${UID}}"

RUN_ID=""
RUN_DIR=""
PROJECT_NAME=""
NETWORK_NAME=""
CONFIG_VOLUME=""
UPLOADS_VOLUME=""
LOGS_VOLUME=""
BACKEND_CONTAINER=""
POSTGRES_CONTAINER=""
REDIS_CONTAINER=""
ENV_FILE=""
COMPOSE_FILE=""
FIXTURE_DIR=""
REQUEST_DIR=""
HTTP_PORT=""
RUN_INITIALIZED=false
RESOURCES_STARTED=false

info() {
  printf '%s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

on_error() {
  local status=$? line="${BASH_LINENO[0]:-${LINENO}}"
  printf 'ERROR: E2E command failed (status=%s, line=%s)\n' "${status}" "${line}" >&2
  return "${status}"
}

trap on_error ERR

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

validate_run_id() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]{7,62}$ ]] || die "无效 E2E run id"
}

derive_resource_names() {
  local run_id="$1"
  validate_run_id "${run_id}"
  RUN_ID="${run_id}"
  PROJECT_NAME="yunlume-e2e-${RUN_ID}"
  NETWORK_NAME="${PROJECT_NAME}-net"
  CONFIG_VOLUME="${PROJECT_NAME}-config"
  UPLOADS_VOLUME="${PROJECT_NAME}-uploads"
  LOGS_VOLUME="${PROJECT_NAME}-logs"
  BACKEND_CONTAINER="${PROJECT_NAME}-backend"
  POSTGRES_CONTAINER="${PROJECT_NAME}-postgres"
  REDIS_CONTAINER="${PROJECT_NAME}-redis"
  RUN_DIR="${STATE_ROOT}/${RUN_ID}"
}

assert_safe_state_root() {
  [[ "${STATE_ROOT}" == /* ]] || die "E2E_STATE_ROOT 必须是绝对路径"
  [[ "${STATE_ROOT}" != "/" && "${STATE_ROOT}" != "/var" && "${STATE_ROOT}" != "/var/tmp" ]] ||
    die "E2E_STATE_ROOT 范围过宽"
  [[ "${STATE_ROOT}" != "${PROJECT_DIR}" ]] || die "状态目录不能是源码目录"
}

prepare_state_root() {
  assert_safe_state_root
  if [[ -e "${STATE_ROOT}" || -L "${STATE_ROOT}" ]]; then
    [[ -d "${STATE_ROOT}" && ! -L "${STATE_ROOT}" ]] ||
      die "E2E_STATE_ROOT 必须是非符号链接目录"
  else
    install -d -m 0700 -- "${STATE_ROOT}"
  fi
  [[ "$(stat -c %u -- "${STATE_ROOT}")" == "${UID}" ]] ||
    die "E2E_STATE_ROOT 不属于当前用户"
  chmod 0700 -- "${STATE_ROOT}"
  local root_real
  root_real="$(readlink -f -- "${STATE_ROOT}")"
  [[ "${root_real}" == "${STATE_ROOT}" ]] ||
    die "E2E_STATE_ROOT 必须是规范化绝对路径"
}

acquire_lock() {
  require_command flock
  # The lock lives inside the already verified 0700 state directory; using a
  # predictable file directly under /tmp would permit a symlink-truncation trap.
  [[ ! -L "${STATE_ROOT}/.lock" ]] || die "E2E lock 不能是符号链接"
  exec 9>"${STATE_ROOT}/.lock"
  flock -n 9 || die "另一个隔离安装 E2E 正在运行"
}

compose() {
  docker compose \
    --project-name "${PROJECT_NAME}" \
    --project-directory "${RUN_DIR}" \
    --env-file "${ENV_FILE}" \
    --file "${COMPOSE_FILE}" \
    "$@"
}

resource_exists() {
  local kind="$1" name="$2" names
  # 只有完整成功的枚举才能证明不存在；不得把 inspect/daemon 错误解释为空资源。
  case "${kind}" in
    container) names="$(docker container ls --all --format '{{json .Names}}')" || return 1 ;;
    network|volume) names="$(docker "${kind}" ls --format '{{json .Name}}')" || return 1 ;;
    *) return 1 ;;
  esac
  python3 -c '
import json
import sys
try:
    names = [json.loads(line) for line in sys.stdin.read().splitlines() if line]
    if any(not isinstance(name, str) or not name for name in names):
        raise ValueError("invalid resource name")
except ValueError:
    print("ERROR: Docker resource listing is invalid", file=sys.stderr)
    raise SystemExit(1)
raise SystemExit(0 if sys.argv[1] in names else 44)
' "${name}" <<<"${names}"
}

remove_owned_resource() {
  local kind="$1" name="$2" status format metadata target
  if resource_exists "${kind}" "${name}"; then
    :
  else
    status=$?
    [[ "${status}" == 44 ]] && return 0
    return 1
  fi
  case "${kind}" in
    container) format="[{{json .Name}},{{json .Id}},{{json (index .Config.Labels \"${LABEL_KEY}\")}}]" ;;
    network) format="[{{json .Name}},{{json .Id}},{{json (index .Labels \"${LABEL_KEY}\")}}]" ;;
    volume) format="[{{json .Name}},{{json .Name}},{{json (index .Labels \"${LABEL_KEY}\")}}]" ;;
    *) return 1 ;;
  esac
  # 一次 inspect 绑定名称、归属和不可变 ID；非零退出即使带有效 stdout 也不可采用。
  metadata="$(docker "${kind}" inspect --format "${format}" "${name}")" || return 1
  target="$(python3 -c '
import json
import re
import sys
try:
    kind, expected_name, run_id = sys.argv[1:]
    value = json.load(sys.stdin)
    if not isinstance(value, list) or len(value) != 3:
        raise ValueError("invalid inspection")
    name, identifier, label = value
    if kind == "container" and isinstance(name, str):
        name = name[1:] if name.startswith("/") else name
    if name != expected_name or label != run_id:
        raise ValueError("resource ownership differs")
    if kind == "volume":
        if identifier != expected_name:
            raise ValueError("volume identity differs")
    elif not isinstance(identifier, str) or re.fullmatch(r"[0-9a-f]{64}", identifier) is None:
        raise ValueError("invalid resource ID")
    print(identifier)
except (TypeError, ValueError):
    print("REFUSED: Docker resource identity or ownership is not verified", file=sys.stderr)
    raise SystemExit(1)
' "${kind}" "${name}" "${RUN_ID}" <<<"${metadata}")" || return 1
  if [[ "${kind}" == container ]]; then
    docker container rm --force "${target}" >/dev/null || return 1
  else
    docker "${kind}" rm "${target}" >/dev/null || return 1
  fi
  # 删除调用成功后还要确认精确名称已不存在；同名替换或查询失败均保留恢复材料。
  if resource_exists "${kind}" "${name}"; then
    printf 'ERROR: 删除后仍存在 %s 资源: %s\n' "${kind}" "${name}" >&2
    return 1
  else
    status=$?
    [[ "${status}" == 44 ]] || return 1
  fi
}

remove_owned_container() { remove_owned_resource container "$1"; }
remove_owned_network() { remove_owned_resource network "$1"; }
remove_owned_volume() { remove_owned_resource volume "$1"; }

cleanup_resources() {
  local failed=0
  remove_owned_container "${BACKEND_CONTAINER}" || failed=1
  remove_owned_container "${REDIS_CONTAINER}" || failed=1
  remove_owned_container "${POSTGRES_CONTAINER}" || failed=1
  remove_owned_network "${NETWORK_NAME}" || failed=1
  remove_owned_volume "${CONFIG_VOLUME}" || failed=1
  remove_owned_volume "${UPLOADS_VOLUME}" || failed=1
  remove_owned_volume "${LOGS_VOLUME}" || failed=1
  return "${failed}"
}

remove_run_directory() {
  [[ -n "${RUN_DIR}" && ! -L "${RUN_DIR}" ]] || return 1
  [[ -e "${RUN_DIR}" ]] || return 0
  [[ -d "${RUN_DIR}" ]] || return 1
  local root_real run_real
  root_real="$(readlink -f -- "${STATE_ROOT}")" || return 1
  run_real="$(readlink -f -- "${RUN_DIR}")" || return 1
  [[ "${run_real}" == "${root_real}/${RUN_ID}" ]] || {
    printf 'REFUSED: 拒绝清理未验证的运行目录: %s\n' "${run_real}" >&2
    return 1
  }
  rm -rf --one-file-system -- "${run_real}" || return 1
  [[ ! -e "${RUN_DIR}" && ! -L "${RUN_DIR}" ]]
}

register_exit_handlers() {
  trap on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

on_exit() {
  local status=$? cleanup_status=0
  trap - EXIT ERR
  trap '' INT TERM
  set +e
  if [[ "${RUN_INITIALIZED}" == true ]]; then
    cleanup_resources || cleanup_status=$?
    if (( cleanup_status == 0 )); then
      remove_run_directory || cleanup_status=$?
    fi
    if (( cleanup_status == 0 )); then
      info "隔离 E2E 资源已精确清理: ${RUN_ID}"
    else
      printf 'ERROR: 精确清理未完成；保留状态目录以供 cleanup: %s\n' "${RUN_DIR}" >&2
    fi
  fi
  if (( status != 0 )); then
    exit "${status}"
  fi
  exit "${cleanup_status}"
}

assert_resource_names_unused() {
  local name
  for name in "${BACKEND_CONTAINER}" "${POSTGRES_CONTAINER}" "${REDIS_CONTAINER}"; do
    assert_resource_name_unused container "${name}" || return 1
  done
  assert_resource_name_unused network "${NETWORK_NAME}" || return 1
  for name in "${CONFIG_VOLUME}" "${UPLOADS_VOLUME}" "${LOGS_VOLUME}"; do
    assert_resource_name_unused volume "${name}" || return 1
  done
  return 0
}

assert_resource_name_unused() {
  local status
  if resource_exists "$1" "$2"; then
    printf 'ERROR: 唯一 %s 资源名已存在: %s\n' "$1" "$2" >&2
    return 1
  else
    status=$?
    [[ "${status}" == 44 ]] || return 1
  fi
}

choose_loopback_port() {
  python3 <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

write_manifest() {
  cat >"${RUN_DIR}/resource-manifest" <<EOF
RUN_ID=${RUN_ID}
PROJECT_NAME=${PROJECT_NAME}
NETWORK_NAME=${NETWORK_NAME}
CONFIG_VOLUME=${CONFIG_VOLUME}
UPLOADS_VOLUME=${UPLOADS_VOLUME}
LOGS_VOLUME=${LOGS_VOLUME}
BACKEND_CONTAINER=${BACKEND_CONTAINER}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER}
REDIS_CONTAINER=${REDIS_CONTAINER}
EOF
  chmod 0600 "${RUN_DIR}/resource-manifest"
}

generate_tls_fixture() {
  local ca_key="${FIXTURE_DIR}/ca.key"
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 2 \
    -subj "/CN=yunlume-e2e-${RUN_ID}" \
    -keyout "${ca_key}" -out "${FIXTURE_DIR}/ca.crt" >/dev/null 2>&1

  cat >"${FIXTURE_DIR}/postgres.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:postgres
EOF
  openssl req -newkey rsa:2048 -sha256 -nodes -subj "/CN=postgres" \
    -keyout "${FIXTURE_DIR}/postgres.key" -out "${FIXTURE_DIR}/postgres.csr" >/dev/null 2>&1
  openssl x509 -req -sha256 -days 2 -in "${FIXTURE_DIR}/postgres.csr" \
    -CA "${FIXTURE_DIR}/ca.crt" -CAkey "${ca_key}" -CAcreateserial \
    -extfile "${FIXTURE_DIR}/postgres.ext" -out "${FIXTURE_DIR}/postgres.crt" >/dev/null 2>&1

  cat >"${FIXTURE_DIR}/redis.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:redis
EOF
  openssl req -newkey rsa:2048 -sha256 -nodes -subj "/CN=redis" \
    -keyout "${FIXTURE_DIR}/redis.key" -out "${FIXTURE_DIR}/redis.csr" >/dev/null 2>&1
  openssl x509 -req -sha256 -days 2 -in "${FIXTURE_DIR}/redis.csr" \
    -CA "${FIXTURE_DIR}/ca.crt" -CAkey "${ca_key}" -CAcreateserial \
    -extfile "${FIXTURE_DIR}/redis.ext" -out "${FIXTURE_DIR}/redis.crt" >/dev/null 2>&1

  rm -f -- "${ca_key}" "${FIXTURE_DIR}/ca.srl" \
    "${FIXTURE_DIR}/postgres.csr" "${FIXTURE_DIR}/redis.csr"
  chmod 0600 "${FIXTURE_DIR}"/*.key "${FIXTURE_DIR}"/*.crt
}

write_fixture_scripts() {
  cat >"${FIXTURE_DIR}/start-postgres.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
install -d -o postgres -g postgres -m 0700 /var/lib/postgresql/tls
install -o postgres -g postgres -m 0600 /fixture/postgres.key /var/lib/postgresql/tls/server.key
install -o postgres -g postgres -m 0600 /fixture/postgres.crt /var/lib/postgresql/tls/server.crt
install -o postgres -g postgres -m 0600 /fixture/ca.crt /var/lib/postgresql/tls/ca.crt
exec /usr/local/bin/docker-entrypoint.sh "$@"
SH

  cat >"${FIXTURE_DIR}/init-postgres.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
psql --set=ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres \
  --set=nav_password="${NAV_DB_PASSWORD}" <<'SQL'
CREATE ROLE nav_e2e WITH LOGIN PASSWORD :'nav_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;
CREATE DATABASE navigation OWNER nav_e2e;
SQL

psql --set=ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname navigation <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO nav_e2e;
GRANT USAGE, CREATE ON SCHEMA public TO nav_e2e;
SQL

cat >"${PGDATA}/pg_hba.conf" <<'HBA'
local   all  all                      trust
hostssl all  all  0.0.0.0/0          scram-sha-256
hostssl all  all  ::/0               scram-sha-256
hostnossl all all  0.0.0.0/0         reject
hostnossl all all  ::/0              reject
HBA
chmod 0600 "${PGDATA}/pg_hba.conf"
touch "${PGDATA}/.nav-e2e-ready"
SH

  cat >"${FIXTURE_DIR}/start-redis.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
install -d -o redis -g redis -m 0700 /data/tls
install -o redis -g redis -m 0600 /fixture/redis.key /data/tls/server.key
install -o redis -g redis -m 0600 /fixture/redis.crt /data/tls/server.crt
install -o redis -g redis -m 0600 /fixture/ca.crt /data/tls/ca.crt
printf 'user default off\nuser nav_e2e on >%s ~nav:* +ping +select +info +set +get +del +eval +evalsha +exists +incr +psetex +pexpire\n' \
  "${NAV_REDIS_PASSWORD}" >/data/users.acl
chown redis:redis /data/users.acl
chmod 0600 /data/users.acl
exec /usr/local/bin/docker-entrypoint.sh "$@"
SH
  chmod 0700 "${FIXTURE_DIR}/start-postgres.sh" "${FIXTURE_DIR}/start-redis.sh"
  chmod 0555 "${FIXTURE_DIR}/init-postgres.sh"
}

write_compose_file() {
  cat >"${COMPOSE_FILE}" <<'YAML'
name: ${E2E_PROJECT_NAME:?}

services:
  postgres:
    image: ${E2E_POSTGRES_IMAGE:?}
    container_name: ${E2E_POSTGRES_CONTAINER:?}
    restart: "no"
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${E2E_POSTGRES_BOOTSTRAP_PASSWORD:?}
      POSTGRES_DB: postgres
      POSTGRES_INITDB_ARGS: --auth-host=scram-sha-256 --auth-local=trust
      NAV_DB_PASSWORD: ${E2E_DB_PASSWORD:?}
    entrypoint: [/fixture/start-postgres.sh]
    command:
      - postgres
      - -c
      - listen_addresses=*
      - -c
      - ssl=on
      - -c
      - ssl_cert_file=/var/lib/postgresql/tls/server.crt
      - -c
      - ssl_key_file=/var/lib/postgresql/tls/server.key
      - -c
      - ssl_ca_file=/var/lib/postgresql/tls/ca.crt
      - -c
      - ssl_min_protocol_version=TLSv1.2
      - -c
      - password_encryption=scram-sha-256
    tmpfs:
      - /var/lib/postgresql/data:rw,nosuid,nodev,noexec,mode=0700
    volumes:
      - ${E2E_FIXTURE_DIR:?}/start-postgres.sh:/fixture/start-postgres.sh:ro
      - ${E2E_FIXTURE_DIR:?}/init-postgres.sh:/docker-entrypoint-initdb.d/00-nav-e2e.sh:ro
      - ${E2E_FIXTURE_DIR:?}/postgres.key:/fixture/postgres.key:ro
      - ${E2E_FIXTURE_DIR:?}/postgres.crt:/fixture/postgres.crt:ro
      - ${E2E_FIXTURE_DIR:?}/ca.crt:/fixture/ca.crt:ro
    healthcheck:
      test:
        - CMD-SHELL
        - test -f "$${PGDATA}/.nav-e2e-ready" && pg_isready -q -U postgres -d postgres
      interval: 2s
      timeout: 3s
      retries: 45
      start_period: 5s
    networks:
      e2e:
        aliases: [postgres]
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}
    security_opt: [no-new-privileges:true]

  redis:
    image: ${E2E_REDIS_IMAGE:?}
    container_name: ${E2E_REDIS_CONTAINER:?}
    restart: "no"
    environment:
      NAV_REDIS_PASSWORD: ${E2E_REDIS_PASSWORD:?}
    entrypoint: [/fixture/start-redis.sh]
    command:
      - redis-server
      - --bind
      - 0.0.0.0
      - --protected-mode
      - "yes"
      - --port
      - "6380"
      - --tls-port
      - "6379"
      - --tls-cert-file
      - /data/tls/server.crt
      - --tls-key-file
      - /data/tls/server.key
      - --tls-ca-cert-file
      - /data/tls/ca.crt
      - --tls-auth-clients
      - "no"
      - --aclfile
      - /data/users.acl
      - --save
      - ""
      - --appendonly
      - "no"
    tmpfs:
      - /data:rw,nosuid,nodev,noexec,mode=0700
    volumes:
      - ${E2E_FIXTURE_DIR:?}/start-redis.sh:/fixture/start-redis.sh:ro
      - ${E2E_FIXTURE_DIR:?}/redis.key:/fixture/redis.key:ro
      - ${E2E_FIXTURE_DIR:?}/redis.crt:/fixture/redis.crt:ro
      - ${E2E_FIXTURE_DIR:?}/ca.crt:/fixture/ca.crt:ro
    healthcheck:
      test:
        - CMD-SHELL
        - redis-cli --no-auth-warning --tls --cacert /data/tls/ca.crt -h redis -p 6379 -n 1 --user nav_e2e -a "$${NAV_REDIS_PASSWORD}" ping | grep -qx PONG
      interval: 2s
      timeout: 3s
      retries: 45
      start_period: 3s
    networks:
      e2e:
        aliases: [redis]
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}
    security_opt: [no-new-privileges:true]

  backend:
    image: ${E2E_BACKEND_IMAGE:?}
    container_name: ${E2E_BACKEND_CONTAINER:?}
    restart: unless-stopped
    read_only: true
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SERVER_PORT: "8080"
      CACHE_TYPE: redis
      JWT_SECRET: ${E2E_JWT_SECRET:?}
      NAV_BOOTSTRAP_ENABLED: "false"
      NAV_DEMO_DATA_ENABLED: "false"
      NAV_WEB_INSTALL_ENABLED: "true"
      NAV_ALLOW_INSECURE_DATABASE_SETUP: "true"
      NAV_DATABASE_SOURCE: UNCONFIGURED
      NAV_DATABASE_CONFIG_FILE: /app/config/database.properties
      NAV_DATABASE_CONFIGURED_MARKER_FILE: /app/config/database.configured
      NAV_INSTALL_COMPLETED_MARKER_FILE: /app/config/install.completed
      NAV_DATABASE_CA_FILE: /app/config/postgresql-ca.pem
      NAV_DATABASE_TICKET_TTL_SECONDS: "120"
      NAV_DATABASE_AUTO_RESTART: "true"
      NAV_REDIS_SOURCE: ${E2E_NAV_REDIS_SOURCE:-UNCONFIGURED}
      NAV_REDIS_CONFIG_FILE: /app/config/redis.properties
      NAV_REDIS_CONFIGURED_MARKER_FILE: /app/config/redis.configured
      NAV_REDIS_CA_FILE: /app/config/redis-ca.pem
      NAV_REDIS_TICKET_TTL_SECONDS: "120"
      NAV_REDIS_AUTO_RESTART: "true"
      REDIS_HOST: redis
      REDIS_PORT: "6380"
      REDIS_USERNAME: nav_e2e
      REDIS_PASSWORD: ${E2E_REDIS_PASSWORD:?}
      REDIS_DATABASE: "1"
      REDIS_SSL_ENABLED: "false"
      REDIS_CONNECT_TIMEOUT: 3s
      REDIS_READ_TIMEOUT: 3s
      APP_UPLOAD_DIR: /app/uploads
      UPLOAD_DIR: /app/uploads
      APP_UPLOAD_BASE_URL: /uploads
      JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70.0 -Dfile.encoding=UTF-8
    ports:
      - 127.0.0.1:${E2E_HTTP_PORT:?}:8080
    volumes:
      - config_data:/app/config
      - uploads_data:/app/uploads
      - logs_data:/app/logs
    tmpfs:
      - /tmp:rw,nosuid,nodev,noexec,uid=10001,gid=10001,mode=0700
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: [CMD-SHELL, wget -q --spider http://127.0.0.1:8080/api/health]
      interval: 3s
      timeout: 3s
      retries: 40
      start_period: 15s
    networks: [e2e]
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}
    security_opt: [no-new-privileges:true]
    cap_drop: [ALL]
    stop_grace_period: 15s

networks:
  e2e:
    name: ${E2E_NETWORK_NAME:?}
    driver: bridge
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}

volumes:
  config_data:
    name: ${E2E_CONFIG_VOLUME:?}
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}
  uploads_data:
    name: ${E2E_UPLOADS_VOLUME:?}
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}
  logs_data:
    name: ${E2E_LOGS_VOLUME:?}
    labels:
      io.yunlume.install-e2e.run: ${E2E_RUN_ID:?}
YAML
  chmod 0600 "${COMPOSE_FILE}"
}

write_environment_file() {
  local backend_image="$1" postgres_image="$2" redis_image="$3"
  cat >"${ENV_FILE}" <<EOF
E2E_RUN_ID=${RUN_ID}
E2E_PROJECT_NAME=${PROJECT_NAME}
E2E_NETWORK_NAME=${NETWORK_NAME}
E2E_CONFIG_VOLUME=${CONFIG_VOLUME}
E2E_UPLOADS_VOLUME=${UPLOADS_VOLUME}
E2E_LOGS_VOLUME=${LOGS_VOLUME}
E2E_BACKEND_CONTAINER=${BACKEND_CONTAINER}
E2E_POSTGRES_CONTAINER=${POSTGRES_CONTAINER}
E2E_REDIS_CONTAINER=${REDIS_CONTAINER}
E2E_FIXTURE_DIR=${FIXTURE_DIR}
E2E_HTTP_PORT=${HTTP_PORT}
E2E_BACKEND_IMAGE=${backend_image}
E2E_POSTGRES_IMAGE=${postgres_image}
E2E_REDIS_IMAGE=${redis_image}
E2E_POSTGRES_BOOTSTRAP_PASSWORD=$(openssl rand -hex 32)
E2E_DB_PASSWORD=$(openssl rand -hex 32)
E2E_REDIS_PASSWORD=$(openssl rand -hex 32)
E2E_JWT_SECRET=$(openssl rand -hex 48)
E2E_ADMIN_PASSWORD=Ee2!$(openssl rand -hex 18)
E2E_NAV_REDIS_SOURCE=UNCONFIGURED
EOF
  chmod 0600 "${ENV_FILE}"
}

env_value() {
  local key="$1"
  python3 - "${ENV_FILE}" "${key}" <<'PY'
import sys
path, wanted = sys.argv[1:]
for raw in open(path, encoding="utf-8"):
    key, sep, value = raw.rstrip("\n").partition("=")
    if sep and key == wanted:
        print(value)
        raise SystemExit(0)
raise SystemExit(f"missing environment key: {wanted}")
PY
}

generate_request_files() {
  E2E_DB_PASSWORD="$(env_value E2E_DB_PASSWORD)" \
  E2E_REDIS_PASSWORD="$(env_value E2E_REDIS_PASSWORD)" \
  E2E_ADMIN_PASSWORD="$(env_value E2E_ADMIN_PASSWORD)" \
  E2E_CA_FILE="${FIXTURE_DIR}/ca.crt" \
  E2E_REQUEST_DIR="${REQUEST_DIR}" python3 <<'PY'
import json
import os
from pathlib import Path

out = Path(os.environ["E2E_REQUEST_DIR"])
ca = Path(os.environ["E2E_CA_FILE"]).read_text(encoding="ascii")
payloads = {
    "database-test.json": {
        "host": "postgres", "port": 5432, "database": "navigation",
        "username": "nav_e2e", "password": os.environ["E2E_DB_PASSWORD"],
        "sslMode": "VERIFY_FULL", "caCertificatePem": ca,
        "acknowledgeUnverifiedTls": False,
    },
    "redis-test.json": {
        "host": "redis", "port": 6379, "username": "nav_e2e",
        "password": os.environ["E2E_REDIS_PASSWORD"], "database": 1,
        "tlsMode": "CUSTOM_CA", "caCertificatePem": ca,
        "acknowledgeInsecureTransport": False,
        "connectTimeoutSeconds": 3, "readTimeoutSeconds": 3,
    },
    "install-complete.json": {
        "siteName": "yunlume E2E", "siteDescription": "isolated installer verification",
        "username": "e2e_admin", "nickname": "E2E Admin",
        "password": os.environ["E2E_ADMIN_PASSWORD"],
        "confirmPassword": os.environ["E2E_ADMIN_PASSWORD"],
    },
    "login.json": {
        "username": "e2e_admin", "password": os.environ["E2E_ADMIN_PASSWORD"],
    },
}
for name, value in payloads.items():
    (out / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
PY

  cat >"${RUN_DIR}/curl.conf" <<'EOF'
header = "Content-Type: application/json"
connect-timeout = 3
max-time = 45
silent
show-error
EOF
  chmod 0600 "${REQUEST_DIR}"/*.json "${RUN_DIR}/curl.conf"
}

HTTP_STATUS=""
HTTP_BODY=""

request() {
  local method="$1" path="$2" body_file="${3:-}"
  HTTP_BODY="${RUN_DIR}/response.json"
  local args=(--config "${RUN_DIR}/curl.conf" --request "${method}" \
    --url "http://127.0.0.1:${HTTP_PORT}${path}" --output "${HTTP_BODY}" \
    --write-out '%{http_code}')
  [[ -z "${body_file}" ]] || args+=(--data-binary "@${body_file}")
  if ! HTTP_STATUS="$(curl "${args[@]}")"; then
    HTTP_STATUS="000"
    return 1
  fi
}

assert_success_json() {
  local expected_http="$1"
  if [[ "${HTTP_STATUS}" != "${expected_http}" ]]; then
    python3 - "${HTTP_BODY}" <<'PY' >&2 || true
import json, sys
try:
    body = json.load(open(sys.argv[1], encoding="utf-8"))
    print(f"API_ERROR code={body.get('code')!r} message={body.get('message')!r}")
except Exception:
    print("API_ERROR response body is not valid JSON")
PY
    die "API 返回 HTTP ${HTTP_STATUS}，期望 ${expected_http}"
  fi
  python3 - "${HTTP_BODY}" "${expected_http}" <<'PY'
import json, sys
body = json.load(open(sys.argv[1], encoding="utf-8"))
expected = int(sys.argv[2])
if body.get("code") != expected:
    raise SystemExit("response code does not match HTTP status")
PY
}

assert_error_json() {
  local expected_http="$1" message_fragment="${2:-}"
  [[ "${HTTP_STATUS}" == "${expected_http}" ]] ||
    die "API 返回 HTTP ${HTTP_STATUS}，期望错误 ${expected_http}"
  python3 - "${HTTP_BODY}" "${expected_http}" "${message_fragment}" <<'PY'
import json, sys
body = json.load(open(sys.argv[1], encoding="utf-8"))
expected = int(sys.argv[2])
fragment = sys.argv[3]
if body.get("code") != expected:
    raise SystemExit("error response code does not match HTTP status")
if fragment and fragment not in str(body.get("message", "")):
    raise SystemExit("error response does not have the expected semantic message")
PY
}

assert_status_body() {
  local expected="$1"
  python3 - "${HTTP_BODY}" "${expected}" <<'PY'
import json, sys
body = json.load(open(sys.argv[1], encoding="utf-8"))
data = body.get("data") or {}
state = data.get("state")
if state != sys.argv[2]:
    raise SystemExit(f"expected state {sys.argv[2]}, got {state}")
required = {"DATABASE_REQUIRED": True, "REDIS_REQUIRED": True,
            "REQUIRED": True, "COMPLETED": False}[state]
if data.get("installationRequired") is not required:
    raise SystemExit("installationRequired is inconsistent")
if state == "REQUIRED" and data.get("ready") is not True:
    raise SystemExit("REQUIRED state is not ready")
PY
}

wait_for_state() {
  local expected="$1" timeout="${2:-150}" deadline
  deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if request GET /api/install/status "" && [[ "${HTTP_STATUS}" == "200" ]]; then
      if python3 - "${HTTP_BODY}" "${expected}" <<'PY'
import json, sys
try:
    state = (json.load(open(sys.argv[1], encoding="utf-8")).get("data") or {}).get("state")
except Exception:
    raise SystemExit(1)
raise SystemExit(0 if state == sys.argv[2] else 1)
PY
      then
        assert_status_body "${expected}"
        info "安装状态达到 ${expected}"
        return 0
      fi
    fi
    sleep 2
  done
  die "等待安装状态 ${expected} 超时"
}

ticket_body_from_response() {
  local output="$1" initialize_schema="${2:-}"
  python3 - "${HTTP_BODY}" "${output}" "${initialize_schema}" <<'PY'
import json, re, sys
source, output, initialize = sys.argv[1:]
data = (json.load(open(source, encoding="utf-8")).get("data") or {})
ticket = data.get("connectionTicket")
if not isinstance(ticket, str) or not re.fullmatch(r"[0-9a-f]{64}", ticket):
    raise SystemExit("missing secure connection ticket")
payload = {"connectionTicket": ticket}
if initialize:
    payload["initializeSchema"] = initialize.lower() == "true"
open(output, "w", encoding="utf-8").write(json.dumps(payload))
PY
  chmod 0600 "${output}"
}

expect_ticket_replay_rejected() {
  local path="$1" body="$2" resource_name="$3" message_fragment
  case "${resource_name}" in
    数据库) message_fragment="数据库连接票据不存在、已使用或已过期" ;;
    Redis) message_fragment="Redis 连接票据不存在、已使用或已过期" ;;
    *) die "未知 ticket 资源类型: ${resource_name}" ;;
  esac
  request POST "${path}" "${body}"
  assert_error_json 409 "${message_fragment}"
  info "${resource_name} 一次性 ticket 重放已以精确 409 语义拒绝"
}

assert_concurrent_install_completion() {
  local first_body="${RUN_DIR}/install-complete-first.json"
  local second_body="${RUN_DIR}/install-complete-second.json"
  local first_status="${RUN_DIR}/install-complete-first.status"
  local second_status="${RUN_DIR}/install-complete-second.status"
  local first_pid second_pid
  local url="http://127.0.0.1:${HTTP_PORT}/api/install/complete"

  curl --config "${RUN_DIR}/curl.conf" --request POST --url "${url}" \
    --data-binary "@${REQUEST_DIR}/install-complete.json" \
    --output "${first_body}" --write-out '%{http_code}' >"${first_status}" &
  first_pid=$!
  curl --config "${RUN_DIR}/curl.conf" --request POST --url "${url}" \
    --data-binary "@${REQUEST_DIR}/install-complete.json" \
    --output "${second_body}" --write-out '%{http_code}' >"${second_status}" &
  second_pid=$!
  wait "${first_pid}"
  wait "${second_pid}"

  python3 - "${first_status}" "${first_body}" "${second_status}" "${second_body}" <<'PY'
import json
import sys

results = []
for status_path, body_path in ((sys.argv[1], sys.argv[2]), (sys.argv[3], sys.argv[4])):
    status = int(open(status_path, encoding="ascii").read().strip())
    body = json.load(open(body_path, encoding="utf-8"))
    if body.get("code") != status:
        raise SystemExit("concurrent completion response code does not match HTTP status")
    results.append((status, body))
if sorted(status for status, _ in results) != [200, 409]:
    raise SystemExit("concurrent completion must produce exactly one success and one conflict")
success = next(body for status, body in results if status == 200)
conflict = next(body for status, body in results if status == 409)
if (success.get("data") or {}).get("installed") is not True:
    raise SystemExit("successful concurrent completion response is invalid")
if "不能再次初始化" not in str(conflict.get("message", "")):
    raise SystemExit("losing concurrent completion did not return the expected conflict")
PY
  info "并发完成请求已验证：仅一个成功，另一个以 409 拒绝"
}

wait_container_health() {
  local container="$1" expected="$2" timeout="${3:-120}" deadline health
  deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    health="$(docker container inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "${container}" 2>/dev/null || true)"
    [[ "${health}" == "${expected}" ]] && return 0
    sleep 2
  done
  die "容器 ${container} 未达到 ${expected}"
}

assert_database_fixture() {
  local ok postgres_major redis_major
  ok="$(docker exec "${POSTGRES_CONTAINER}" psql -At -U postgres -d postgres -c \
    "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication FROM pg_roles WHERE rolname='nav_e2e'")"
  [[ "${ok}" == "t" ]] || die "PostgreSQL E2E 角色不是受限非超级用户"
  postgres_major="$(docker exec "${POSTGRES_CONTAINER}" psql -At -U postgres -d postgres -c \
    "SELECT current_setting('server_version_num')::int / 10000")"
  [[ "${postgres_major}" == "17" ]] || die "E2E 必须使用 PostgreSQL 17，实际 major=${postgres_major}"
  redis_major="$(docker exec "${REDIS_CONTAINER}" redis-server --version | \
    sed -n 's/.*v=\([0-9][0-9]*\.[0-9][0-9]*\).*/\1/p')"
  [[ "${redis_major}" == "7.4" ]] || die "E2E 必须使用 Redis 7.4，实际=${redis_major}"
}

assert_config_permissions_and_uuid() {
  local phase="$1" expected_uuid database_marker_uuid redis_config_uuid \
    redis_marker_uuid completed_uuid actual_uuid
  docker exec "${BACKEND_CONTAINER}" sh -eu -c '
    test "$(stat -c %a /app/config)" = 700
    for file in database.properties database.configured postgresql-ca.pem; do
      test -f "/app/config/${file}"
      test "$(stat -c %a "/app/config/${file}")" = 600
    done
  '
  if [[ "${phase}" == "redis" || "${phase}" == "completed" ]]; then
    docker exec "${BACKEND_CONTAINER}" sh -eu -c '
      for file in redis.properties redis.configured redis-ca.pem; do
        test -f "/app/config/${file}"
        test "$(stat -c %a "/app/config/${file}")" = 600
      done
    '
  fi
  if [[ "${phase}" == "completed" ]]; then
    docker exec "${BACKEND_CONTAINER}" sh -eu -c '
      test -f /app/config/install.completed
      test "$(stat -c %a /app/config/install.completed)" = 600
    '
  fi
  expected_uuid="$(docker exec "${BACKEND_CONTAINER}" sh -eu -c \
    "awk -F= '\$1 == \"nav.database-config.expected-instance-id\" { print \$2 }' /app/config/database.properties")"
  database_marker_uuid="$(docker exec "${BACKEND_CONTAINER}" sh -eu -c \
    "awk -F= '\$1 == \"instance-id\" { print \$2 }' /app/config/database.configured")"
  actual_uuid="$(docker exec "${POSTGRES_CONTAINER}" psql -At -U postgres -d navigation -c \
    'SELECT install_instance_id::text FROM public.site_config LIMIT 1')"
  [[ "${expected_uuid}" =~ ^[0-9a-f-]{36}$ \
    && "${expected_uuid}" == "${database_marker_uuid}" \
    && "${expected_uuid}" == "${actual_uuid}" ]] ||
    die "持久配置 UUID 与 PostgreSQL 实例 UUID 不一致"
  if [[ "${phase}" == "redis" || "${phase}" == "completed" ]]; then
    redis_config_uuid="$(docker exec "${BACKEND_CONTAINER}" sh -eu -c \
      "awk -F= '\$1 == \"nav.redis-config.expected-instance-id\" { print \$2 }' /app/config/redis.properties")"
    redis_marker_uuid="$(docker exec "${BACKEND_CONTAINER}" sh -eu -c \
      "awk -F= '\$1 == \"instance-id\" { print \$2 }' /app/config/redis.configured")"
    [[ "${expected_uuid}" == "${redis_config_uuid}" \
      && "${expected_uuid}" == "${redis_marker_uuid}" ]] ||
      die "Redis 持久配置 UUID 与数据库实例 UUID 不一致"
  fi
  if [[ "${phase}" == "completed" ]]; then
    completed_uuid="$(docker exec "${BACKEND_CONTAINER}" sh -eu -c \
      "awk -F= '\$1 == \"instance-id\" { print \$2 }' /app/config/install.completed")"
    [[ "${expected_uuid}" == "${completed_uuid}" ]] ||
      die "安装完成标记 UUID 与数据库实例 UUID 不一致"
  fi
}

assert_redis_acl_denies_management() {
  docker exec "${REDIS_CONTAINER}" sh -eu -c '
    probe="nav:e2e:acl:$$"
    test "$(redis-cli --no-auth-warning --tls --cacert /data/tls/ca.crt -h redis -p 6379 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" SET "$probe" allowed PX 60000)" = OK
    test "$(redis-cli --no-auth-warning --tls --cacert /data/tls/ca.crt -h redis -p 6379 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" GET "$probe")" = allowed
    test "$(redis-cli --no-auth-warning --tls --cacert /data/tls/ca.crt -h redis -p 6379 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" DEL "$probe")" = 1
    output="$(redis-cli --no-auth-warning --tls --cacert /data/tls/ca.crt -h redis -p 6379 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" CONFIG GET dir 2>&1 || true)"
    printf "%s" "$output" | grep -q NOPERM
    output="$(redis-cli --no-auth-warning --tls --cacert /data/tls/ca.crt -h redis -p 6379 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" SET e2e:foreign denied 2>&1 || true)"
    printf "%s" "$output" | grep -q NOPERM
  ' || die "Redis ACL 未正确限制管理命令或非 nav:* 键空间"
}

wait_health_up() {
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    if request GET /api/health "" && [[ "${HTTP_STATUS}" == "200" ]]; then
      if python3 - "${HTTP_BODY}" <<'PY'
import json, sys
data = (json.load(open(sys.argv[1], encoding="utf-8")).get("data") or {})
raise SystemExit(0 if data.get("status") == "UP" else 1)
PY
      then
        return 0
      fi
    fi
    sleep 2
  done
  die "后端健康状态未恢复为 UP"
}

assert_health_fails_with_redis_down() {
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    [[ "$(docker container inspect --format '{{.State.Running}}' "${BACKEND_CONTAINER}")" == "true" ]] ||
      die "Redis 断线测试期间 backend 意外退出"
    if request GET /api/health "" && [[ "${HTTP_STATUS}" == "500" ]]; then
      assert_error_json 500 "服务器内部错误"
      return 0
    fi
    sleep 2
  done
  die "Redis 停止后 backend 未在持续运行状态下返回预期健康失败"
}

assert_completed_legacy_fallback_blocked() {
  docker exec "${REDIS_CONTAINER}" sh -eu -c '
    test "$(redis-cli --no-auth-warning -h 127.0.0.1 -p 6380 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" ping)" = PONG
  ' || die "LEGACY_ENV 对照 Redis 本身不可用，无法证明完成态拒绝回退"
  docker exec "${BACKEND_CONTAINER}" sh -eu -c '
    rm -f -- /app/config/redis.properties /app/config/redis.configured /app/config/redis-ca.pem
  '
  docker container stop --time 15 "${BACKEND_CONTAINER}" >/dev/null
  E2E_NAV_REDIS_SOURCE=LEGACY_ENV compose up -d --no-deps --no-build --force-recreate backend >/dev/null
  local deadline=$((SECONDS + 75))
  while (( SECONDS < deadline )); do
    if request GET /api/health "" && [[ "${HTTP_STATUS}" == "200" ]]; then
      die "完成态在 Redis 工件丢失后错误回退到 LEGACY_ENV"
    fi
    if docker logs "${BACKEND_CONTAINER}" 2>&1 | \
      grep -Fq 'Completed installation is missing its managed Redis configuration'; then
      local restart_count
      restart_count="$(docker container inspect --format '{{.RestartCount}}' "${BACKEND_CONTAINER}")"
      (( restart_count >= 1 )) || {
        sleep 3
        restart_count="$(docker container inspect --format '{{.RestartCount}}' "${BACKEND_CONTAINER}")"
      }
      (( restart_count >= 1 )) || die "完成态缺失 Redis 工件虽报错，但容器未呈现 fail-closed 重启状态"
      docker exec "${REDIS_CONTAINER}" sh -eu -c '
        test "$(redis-cli --no-auth-warning -h 127.0.0.1 -p 6380 -n 1 --user nav_e2e -a "$NAV_REDIS_PASSWORD" ping)" = PONG
      ' || die "完成态 fail-closed 时 LEGACY_ENV 对照 Redis 已不可用"
      info "完成态 Redis 工件丢失已由明确启动错误拒绝，未回退 LEGACY_ENV"
      return 0
    fi
    sleep 2
  done
  die "未观测到完成态缺失受管 Redis 配置的明确 fail-closed 启动错误"
}

run_e2e() {
  [[ "${CONFIRM_ISOLATED_INSTALL_E2E:-}" == "RUN-ISOLATED-INSTALL-E2E" ]] ||
    die "执行前必须设置 CONFIRM_ISOLATED_INSTALL_E2E=RUN-ISOLATED-INSTALL-E2E"
  register_exit_handlers
  local backend_image="${E2E_BACKEND_IMAGE:-}" \
    postgres_image="${E2E_POSTGRES_IMAGE:-postgres:17-bookworm}" \
    redis_image="${E2E_REDIS_IMAGE:-redis:7.4-bookworm}"
  [[ -n "${backend_image}" ]] || die "必须显式设置 E2E_BACKEND_IMAGE"
  [[ "${backend_image}" =~ ^[A-Za-z0-9._/@:+-]+$ ]] || die "后端镜像引用格式无效"
  [[ "${postgres_image}" =~ ^[A-Za-z0-9._/@:+-]+$ ]] || die "PostgreSQL 镜像引用格式无效"
  [[ "${redis_image}" =~ ^[A-Za-z0-9._/@:+-]+$ ]] || die "Redis 镜像引用格式无效"

  require_command docker
  require_command openssl
  require_command python3
  require_command curl
  require_command readlink
  require_command stat
  require_command install
  require_command chmod
  prepare_state_root
  docker compose version >/dev/null
  docker info >/dev/null
  docker image inspect "${backend_image}" >/dev/null 2>&1 ||
    die "后端 E2E 镜像不存在: ${backend_image}"
  derive_resource_names "$(date -u +'%Y%m%dt%H%M%Sz')-$$-$(openssl rand -hex 4)"
  acquire_lock
  [[ ! -e "${RUN_DIR}" ]] || die "唯一运行目录已存在"
  install -d -m 0700 "${RUN_DIR}" "${RUN_DIR}/fixture" "${RUN_DIR}/requests"
  FIXTURE_DIR="${RUN_DIR}/fixture"
  REQUEST_DIR="${RUN_DIR}/requests"
  ENV_FILE="${RUN_DIR}/e2e.env"
  COMPOSE_FILE="${RUN_DIR}/compose.yml"
  HTTP_PORT="$(choose_loopback_port)"
  RUN_INITIALIZED=true
  write_manifest
  info "隔离 E2E run id: ${RUN_ID}"
  info "紧急精确清理: CONFIRM_ISOLATED_INSTALL_E2E_CLEANUP=CLEAN-ISOLATED-INSTALL-E2E $0 cleanup ${RUN_ID}"

  assert_resource_names_unused
  generate_tls_fixture
  write_fixture_scripts
  write_environment_file "${backend_image}" "${postgres_image}" "${redis_image}"
  write_compose_file
  generate_request_files
  compose config --quiet

  info "启动唯一隔离 PostgreSQL、Redis 与 backend 容器"
  compose up -d --no-build postgres redis backend >/dev/null
  RESOURCES_STARTED=true
  wait_container_health "${POSTGRES_CONTAINER}" healthy 120
  wait_container_health "${REDIS_CONTAINER}" healthy 120
  wait_for_state DATABASE_REQUIRED 180
  assert_database_fixture

  info "测试并接管 VERIFY_FULL PostgreSQL 17 空库"
  request POST /api/install/database/test "${REQUEST_DIR}/database-test.json"
  assert_success_json 200
  python3 - "${HTTP_BODY}" <<'PY'
import json, sys
data = (json.load(open(sys.argv[1], encoding="utf-8")).get("data") or {})
if data.get("schemaState") != "EMPTY" or data.get("requiresInitialization") is not True:
    raise SystemExit("database candidate is not an empty schema")
PY
  ticket_body_from_response "${REQUEST_DIR}/database-consume.json" false
  request POST /api/install/database/configure "${REQUEST_DIR}/database-consume.json"
  assert_error_json 400 "空数据库必须确认初始化结构"
  expect_ticket_replay_rejected /api/install/database/configure \
    "${REQUEST_DIR}/database-consume.json" "数据库"

  request POST /api/install/database/test "${REQUEST_DIR}/database-test.json"
  assert_success_json 200
  ticket_body_from_response "${REQUEST_DIR}/database-configure.json" true
  request POST /api/install/database/configure "${REQUEST_DIR}/database-configure.json"
  assert_success_json 200
  wait_for_state REDIS_REQUIRED 180
  assert_config_permissions_and_uuid database

  info "测试并接管 CUSTOM_CA Redis 7.4 ACL 用户"
  request POST /api/install/redis/test "${REQUEST_DIR}/redis-test.json"
  assert_success_json 200
  ticket_body_from_response "${REQUEST_DIR}/redis-consume.json"
  docker container stop --time 10 "${REDIS_CONTAINER}" >/dev/null
  request POST /api/install/redis/configure "${REQUEST_DIR}/redis-consume.json"
  assert_error_json 503
  docker container start "${REDIS_CONTAINER}" >/dev/null
  wait_container_health "${REDIS_CONTAINER}" healthy 90
  expect_ticket_replay_rejected /api/install/redis/configure \
    "${REQUEST_DIR}/redis-consume.json" "Redis"

  request POST /api/install/redis/test "${REQUEST_DIR}/redis-test.json"
  assert_success_json 200
  ticket_body_from_response "${REQUEST_DIR}/redis-configure.json"
  request POST /api/install/redis/configure "${REQUEST_DIR}/redis-configure.json"
  assert_success_json 200
  wait_for_state REQUIRED 180
  assert_config_permissions_and_uuid redis
  assert_redis_acl_denies_management

  request POST /api/install/check ""
  assert_success_json 200
  python3 - "${HTTP_BODY}" <<'PY'
import json, sys
data = (json.load(open(sys.argv[1], encoding="utf-8")).get("data") or {})
if data.get("ready") is not True:
    raise SystemExit("install environment checks are not ready")
PY

  info "并发完成管理员初始化并验证登录"
  assert_concurrent_install_completion
  wait_for_state COMPLETED 90
  assert_config_permissions_and_uuid completed
  wait_health_up

  info "验证完成态拒绝重复配置与重复初始化"
  request POST /api/install/database/configure "${REQUEST_DIR}/database-configure.json"
  assert_error_json 409 "不能更改数据库连接"
  request POST /api/install/redis/configure "${REQUEST_DIR}/redis-configure.json"
  assert_error_json 409 "不能更改 Redis 连接"
  request POST /api/install/complete "${REQUEST_DIR}/install-complete.json"
  assert_error_json 409 "不能再次初始化"

  request POST /api/admin/auth/login "${REQUEST_DIR}/login.json"
  assert_success_json 200
  python3 - "${HTTP_BODY}" <<'PY'
import json, sys
data = (json.load(open(sys.argv[1], encoding="utf-8")).get("data") or {})
if not isinstance(data.get("token"), str) or len(data["token"]) < 32:
    raise SystemExit("administrator login did not return a JWT")
PY

  info "验证 Redis 断线失败关闭与恢复"
  docker container stop --time 10 "${REDIS_CONTAINER}" >/dev/null
  assert_health_fails_with_redis_down
  wait_for_state COMPLETED 30
  docker container start "${REDIS_CONTAINER}" >/dev/null
  wait_container_health "${REDIS_CONTAINER}" healthy 90
  wait_health_up

  info "验证完成态不允许 LEGACY_ENV 回退"
  assert_completed_legacy_fallback_blocked
  info "隔离安装 E2E 全部通过"
}

cleanup_abandoned_run() {
  local run_id="${1:-}"
  [[ "${CONFIRM_ISOLATED_INSTALL_E2E_CLEANUP:-}" == "CLEAN-ISOLATED-INSTALL-E2E" ]] ||
    die "清理前必须设置 CONFIRM_ISOLATED_INSTALL_E2E_CLEANUP=CLEAN-ISOLATED-INSTALL-E2E"
  register_exit_handlers
  require_command docker
  require_command python3
  require_command readlink
  require_command stat
  require_command install
  require_command chmod
  prepare_state_root
  acquire_lock
  derive_resource_names "${run_id}"
  [[ -f "${RUN_DIR}/resource-manifest" && ! -L "${RUN_DIR}/resource-manifest" ]] ||
    die "缺少受保护的 E2E resource-manifest"
  [[ "$(awk -F= '$1 == "RUN_ID" { print $2 }' "${RUN_DIR}/resource-manifest")" == "${RUN_ID}" ]] ||
    die "resource-manifest 与 run id 不匹配"
  RUN_INITIALIZED=true
  # 与 run 共用唯一 EXIT 清理入口，信号和失败不能导致二次清理或丢失原退出码。
}

show_plan() {
  cat <<'EOF'
隔离首次安装 E2E（默认不执行）

资源边界：
  - 唯一 Compose project: yunlume-e2e-<run-id>
  - 唯一 bridge network: <project>-net
  - 恰好三个命名卷: <project>-config / -uploads / -logs
  - PostgreSQL 与 Redis 数据目录均为 tmpfs
  - 只删除带本 run ownership label 的精确资源
  - 不调用 down -v、prune、remove-orphans，不接触 1Panel 项目

Maven、镜像构建全部通过后，显式执行：
  E2E_BACKEND_IMAGE=<已验证后端镜像> \
  CONFIRM_ISOLATED_INSTALL_E2E=RUN-ISOLATED-INSTALL-E2E \
  ./ops/install-e2e.sh run

脚本打印 run id 后如遭强制中断，可精确恢复清理：
  CONFIRM_ISOLATED_INSTALL_E2E_CLEANUP=CLEAN-ISOLATED-INSTALL-E2E \
  ./ops/install-e2e.sh cleanup <run-id>
EOF
}

case "${1:-plan}" in
  plan)
    show_plan
    ;;
  run)
    run_e2e
    ;;
  cleanup)
    cleanup_abandoned_run "${2:-}"
    ;;
  *)
    die "用法: $0 [plan|run|cleanup <run-id>]"
    ;;
esac
