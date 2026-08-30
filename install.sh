#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly PRODUCT_NAME="yunlume"
readonly DEFAULT_INSTALL_DIR="/opt/yunlume"
readonly DEFAULT_PORT="8080"
readonly DEFAULT_RELEASE_BASE_URL="__YUNLUME_RELEASE_BASE_URL__"
readonly INSTALL_LOCK_DIR="/run/lock/yunlume"
readonly INSTALL_LOCK="${INSTALL_LOCK_DIR}/install.lock"

MODE="docker"
VERSION=""
VERSION_EXPLICIT="false"
APP_PORT="${DEFAULT_PORT}"
INSTALL_DIR="${DEFAULT_INSTALL_DIR}"
RELEASE_BASE_URL="${YUNLUME_RELEASE_BASE_URL:-${DEFAULT_RELEASE_BASE_URL}}"
WORK_DIR=""
MANIFEST_FILE=""
RELEASE_ASSET_BASE=""
MANIFEST_VERSION=""
MANIFEST_COMPOSE=""
MANIFEST_COMPOSE_SHA256=""
MANIFEST_BACKEND_IMAGE=""
MANIFEST_FRONTEND_IMAGE=""
MANIFEST_HOST_ARCHIVE=""
MANIFEST_HOST_ARCHIVE_SHA256=""
JAVA_BIN=""
HOST_TRANSACTION_ACTIVE="false"
HOST_HAD_CURRENT="false"
HOST_PREVIOUS_CURRENT=""
HOST_HAD_NGINX_CONFIG="false"
HOST_HAD_SERVICE_FILE="false"
HOST_HAD_NGINX_LINK="false"
HOST_PREVIOUS_NGINX_LINK=""
HOST_SERVICE_WAS_ENABLED="false"
HOST_SERVICE_WAS_ACTIVE="false"
HOST_NGINX_WAS_ACTIVE="false"
HOST_CURRENT_LINK=""
HOST_NGINX_CONFIG=""
HOST_NGINX_LINK=""
HOST_SERVICE_FILE=""
HOST_NGINX_BACKUP=""
HOST_SERVICE_BACKUP=""
HOST_HAD_VERSION="false"
HOST_HAD_MANIFEST="false"
HOST_VERSION_FILE=""
HOST_MANIFEST_FILE=""
HOST_VERSION_BACKUP=""
HOST_MANIFEST_BACKUP=""
HOST_HAD_APP_ENV="false"
HOST_APP_ENV_FILE=""
HOST_APP_ENV_BACKUP=""
HOST_NGINX_WAS_ENABLED="false"
HOST_BACKEND_MUTATED="false"
HOST_NGINX_MUTATED="false"
HOST_ROLLBACK_PORT="${DEFAULT_PORT}"
HOST_TEMPORARY_RELEASE=""
DOCKER_TRANSACTION_ACTIVE="false"
DOCKER_HAD_ENV="false"
DOCKER_HAD_COMPOSE="false"
DOCKER_HAD_VERSION="false"
DOCKER_HAD_MANIFEST="false"
DOCKER_ENV_FILE=""
DOCKER_COMPOSE_FILE=""
DOCKER_VERSION_FILE=""
DOCKER_MANIFEST_FILE=""
DOCKER_ENV_BACKUP=""
DOCKER_COMPOSE_BACKUP=""
DOCKER_VERSION_BACKUP=""
DOCKER_MANIFEST_BACKUP=""
DOCKER_SERVICES_MUTATED="false"
DOCKER_ROLLBACK_PORT="${DEFAULT_PORT}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

usage() {
  cat <<'EOF'
yunlume 安装器

用法:
  install.sh [--mode docker|host] [--version X.Y.Z] [--port PORT]
             [--install-dir /opt/yunlume] [--release-base-url URL]

默认值:
  --mode docker
  --version 当前稳定版本
  --port 8080
  --install-dir /opt/yunlume
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

validate_docker_architecture() {
  local machine_arch
  [[ "${MODE}" == "docker" ]] || return 0
  machine_arch="$(uname -m)"
  case "${machine_arch}" in
    x86_64|amd64|aarch64|arm64)
      ;;
    *)
      die "Docker 模式仅支持 x86_64/amd64 和 aarch64/arm64，当前架构: ${machine_arch}"
      ;;
  esac
}

is_global_ip_address() {
  python3 - "$1" >/dev/null 2>&1 <<'PY'
import ipaddress
import sys

try:
    address = ipaddress.ip_address(sys.argv[1])
except ValueError:
    raise SystemExit(1)
raise SystemExit(0 if address.is_global else 1)
PY
}

detect_public_access_host() {
  local address=""
  command -v ip >/dev/null 2>&1 || return 1

  address="$(
    ip -4 route get 1.1.1.1 2>/dev/null |
      awk '{ for (index = 1; index <= NF; index++) if ($index == "src") { print $(index + 1); exit } }'
  )" || true
  if [[ -n "${address}" ]] && is_global_ip_address "${address}"; then
    printf '%s\n' "${address}"
    return 0
  fi

  address="$(
    ip -6 route get 2001:4860:4860::8888 2>/dev/null |
      awk '{ for (index = 1; index <= NF; index++) if ($index == "src") { print $(index + 1); exit } }'
  )" || true
  if [[ -n "${address}" ]] && is_global_ip_address "${address}"; then
    printf '[%s]\n' "${address}"
    return 0
  fi
  return 1
}

print_access_url() {
  local public_host=""
  if public_host="$(detect_public_access_host)"; then
    info "请访问: http://${public_host}:${APP_PORT}/install"
    return
  fi
  info "未能可靠识别服务器公网地址。"
  info "请将 <服务器公网IP> 替换为实际地址后访问: http://<服务器公网IP>:${APP_PORT}/install"
}

cleanup() {
  local status=$?
  local rollback_failed="false"
  local preserve_failed="false"
  trap - EXIT ERR
  trap '' INT TERM
  set +e
  if [[ "${DOCKER_TRANSACTION_ACTIVE}" == "true" ]]; then
    rollback_docker || rollback_failed="true"
  fi
  if [[ "${HOST_TRANSACTION_ACTIVE}" == "true" ]]; then
    rollback_host || rollback_failed="true"
  fi
  if [[ "${rollback_failed}" == "true" ]]; then
    if ! preserve_rollback_material; then
      preserve_failed="true"
      info "回滚与恢复材料归档均未完成；原始材料保留在 ${WORK_DIR}" >&2
    fi
    status=1
  fi
  if [[ -n "${HOST_TEMPORARY_RELEASE}" ]]; then
    if ! remove_temporary_host_release; then
      info "未能清理宿主机临时版本目录: ${HOST_TEMPORARY_RELEASE}" >&2
      status=1
    fi
  fi
  if [[ "${preserve_failed}" != "true" && -n "${WORK_DIR}" && -d "${WORK_DIR}" ]]; then
    if ! rm -rf -- "${WORK_DIR}"; then
      info "未能清理安装临时目录: ${WORK_DIR}" >&2
      status=1
    fi
  fi
  exit "${status}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

preserve_rollback_material() {
  local recovery_root recovery_dir
  local -a recovery_files=()
  [[ -n "${WORK_DIR}" && -d "${WORK_DIR}" && -d "${INSTALL_DIR}" ]] || return 1
  recovery_root="${INSTALL_DIR}/recovery"
  [[ ! -L "${recovery_root}" ]] || return 1
  install -d -m 0700 "${recovery_root}" || return 1
  recovery_dir="$(mktemp -d "${recovery_root}/rollback.XXXXXXXX")" || return 1
  chmod 0700 "${recovery_dir}" || return 1
  shopt -s nullglob
  recovery_files=(
    "${WORK_DIR}"/previous.*
    "${WORK_DIR}"/failed.*
    "${WORK_DIR}"/release-manifest.json
  )
  shopt -u nullglob
  if (( ${#recovery_files[@]} > 0 )); then
    cp -p -- "${recovery_files[@]}" "${recovery_dir}/" || return 1
  fi
  info "回滚未能完整恢复；恢复材料已保留在 ${recovery_dir}" >&2
}

remove_temporary_host_release() {
  local expected_parent actual_parent
  [[ -n "${HOST_TEMPORARY_RELEASE}" ]] || return 0
  expected_parent="${INSTALL_DIR}/releases"
  [[ "${HOST_TEMPORARY_RELEASE}" == "${expected_parent}/${VERSION}.tmp."* ]] || return 1
  [[ ! -L "${HOST_TEMPORARY_RELEASE}" ]] || return 1
  actual_parent="$(readlink -f -- "$(dirname -- "${HOST_TEMPORARY_RELEASE}")" 2>/dev/null)" ||
    return 1
  [[ "${actual_parent}" == "${expected_parent}" ]] || return 1
  if [[ -d "${HOST_TEMPORARY_RELEASE}" ]]; then
    rm -rf -- "${HOST_TEMPORARY_RELEASE}" || return 1
  elif [[ -e "${HOST_TEMPORARY_RELEASE}" ]]; then
    return 1
  fi
  HOST_TEMPORARY_RELEASE=""
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode)
        [[ $# -ge 2 ]] || die "--mode 缺少参数"
        MODE="$2"
        shift 2
        ;;
      --version)
        [[ $# -ge 2 ]] || die "--version 缺少参数"
        VERSION="${2#v}"
        VERSION_EXPLICIT="true"
        shift 2
        ;;
      --port)
        [[ $# -ge 2 ]] || die "--port 缺少参数"
        APP_PORT="$2"
        shift 2
        ;;
      --install-dir)
        [[ $# -ge 2 ]] || die "--install-dir 缺少参数"
        INSTALL_DIR="${2%/}"
        shift 2
        ;;
      --release-base-url)
        [[ $# -ge 2 ]] || die "--release-base-url 缺少参数"
        RELEASE_BASE_URL="${2%/}"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "未知参数: $1"
        ;;
    esac
  done
}

validate_args() {
  [[ "${MODE}" == "docker" || "${MODE}" == "host" ]] ||
    die "--mode 只支持 docker 或 host"
  [[ "${APP_PORT}" =~ ^[0-9]+$ ]] || die "--port 必须是整数"
  (( APP_PORT >= 1 && APP_PORT <= 65535 )) || die "--port 必须在 1-65535 之间"
  [[ "${INSTALL_DIR}" =~ ^/[A-Za-z0-9._/-]+$ && "${INSTALL_DIR}" != "/" ]] ||
    die "--install-dir 必须是仅含字母、数字、点、下划线、连字符和斜杠的绝对路径"
  [[ "${INSTALL_DIR}" != *"//"* && "${INSTALL_DIR}" != *"/./"* &&
     "${INSTALL_DIR}" != */. && "${INSTALL_DIR}" != *"/../"* &&
     "${INSTALL_DIR}" != */.. ]] ||
    die "--install-dir 不能包含空路径段、. 或 .."
  if [[ -n "${VERSION}" ]]; then
    [[ "${VERSION}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
      die "--version 必须使用不带 v 的 X.Y.Z 格式"
  fi
  [[ -n "${RELEASE_BASE_URL}" ]] || die "发布地址不能为空"
  [[ "${RELEASE_BASE_URL}" != "${DEFAULT_RELEASE_BASE_URL}" ]] ||
    die "当前源码安装脚本尚未写入发布地址，请使用 Release 中的 install.sh 或传入 --release-base-url"
}

validate_install_directory_boundary() {
  local current=""
  local component
  local -a path_parts=()
  IFS=/ read -r -a path_parts <<<"${INSTALL_DIR#/}"
  for component in "${path_parts[@]}"; do
    [[ -n "${component}" ]] || continue
    current="${current}/${component}"
    [[ ! -L "${current}" ]] ||
      die "安装目录及其父目录不能经过符号链接: ${current}"
    if [[ -e "${current}" && ! -d "${current}" ]]; then
      die "安装目录路径中存在非目录节点: ${current}"
    fi
  done
}

acquire_lock() {
  [[ ! -L "${INSTALL_LOCK_DIR}" ]] || die "安装锁目录不能是符号链接"
  install -d -m 0755 "${INSTALL_LOCK_DIR}"
  [[ "$(stat -c '%u' "${INSTALL_LOCK_DIR}")" == "0" ]] || die "安装锁目录必须属于 root"
  [[ ! -L "${INSTALL_LOCK}" ]] || die "安装锁文件不能是符号链接"
  exec 9>"${INSTALL_LOCK}"
  flock -n 9 || die "已有 yunlume 安装或升级任务正在运行"
}

download_file() {
  local url="$1"
  local destination="$2"
  curl --fail --silent --show-error --location \
    --retry 3 --retry-delay 2 --connect-timeout 15 \
    --output "${destination}" "${url}"
}

verify_sha256() {
  local file="$1"
  local expected="${2,,}"
  local actual
  [[ "${expected}" =~ ^[0-9a-f]{64}$ ]] || die "发行清单中的 SHA-256 无效"
  actual="$(sha256sum "${file}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || die "文件校验失败: $(basename -- "${file}")"
}

parse_manifest() {
  local -a values=()
  local parsed_values="${WORK_DIR}/manifest.values"
  if ! python3 - "${MANIFEST_FILE}" >"${parsed_values}" <<'PY'
import json
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
try:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("root must be an object")
    docker = data["docker"]
    host = data["host"]
    if not isinstance(docker, dict) or not isinstance(host, dict):
        raise ValueError("docker and host must be objects")
    values = [
        data["version"],
        docker["compose"],
        docker["composeSha256"],
        docker["backendImage"],
        docker["frontendImage"],
        host["archive"],
        host["archiveSha256"],
    ]
    if not all(isinstance(value, str) and value for value in values):
        raise ValueError("required fields must be non-empty strings")
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", values[0]):
        raise ValueError("version must use X.Y.Z")
    version = re.escape(values[0])
    backend_match = re.fullmatch(
        rf"ghcr\.io/([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)/yunlume-backend:{version}",
        values[3],
    )
    frontend_match = re.fullmatch(
        rf"ghcr\.io/([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)/yunlume-frontend:{version}",
        values[4],
    )
    if not backend_match or not frontend_match:
        raise ValueError("image references must match the manifest version")
    if backend_match.group(1) != frontend_match.group(1):
        raise ValueError("frontend and backend images must use the same GHCR owner")
except (OSError, UnicodeError, json.JSONDecodeError, KeyError, ValueError) as exc:
    print(f"invalid release manifest: {exc}", file=sys.stderr)
    raise SystemExit(2)
for value in values:
    if "\n" in value or "\r" in value:
        print("invalid release manifest: multiline value", file=sys.stderr)
        raise SystemExit(2)
    print(value)
PY
  then
    die "发行清单格式无效"
  fi
  mapfile -t values <"${parsed_values}"
  (( ${#values[@]} == 7 )) || die "发行清单字段不完整"
  MANIFEST_VERSION="${values[0]}"
  MANIFEST_COMPOSE="${values[1]}"
  MANIFEST_COMPOSE_SHA256="${values[2]}"
  MANIFEST_BACKEND_IMAGE="${values[3]}"
  MANIFEST_FRONTEND_IMAGE="${values[4]}"
  MANIFEST_HOST_ARCHIVE="${values[5]}"
  MANIFEST_HOST_ARCHIVE_SHA256="${values[6]}"
}

validate_asset_name() {
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]] || die "发行资源名称无效: $1"
}

load_manifest() {
  local manifest_url
  if [[ -n "${VERSION}" ]]; then
    manifest_url="${RELEASE_BASE_URL}/download/v${VERSION}/release-manifest.json"
  else
    manifest_url="${RELEASE_BASE_URL}/latest/download/release-manifest.json"
  fi
  MANIFEST_FILE="${WORK_DIR}/release-manifest.json"
  info "正在读取发行清单..."
  download_file "${manifest_url}" "${MANIFEST_FILE}"
  parse_manifest
  if [[ -n "${VERSION}" && "${VERSION}" != "${MANIFEST_VERSION}" ]]; then
    die "请求版本 ${VERSION} 与发行清单 ${MANIFEST_VERSION} 不一致"
  fi
  VERSION="${MANIFEST_VERSION}"
  RELEASE_ASSET_BASE="${RELEASE_BASE_URL}/download/v${VERSION}"
}

ensure_install_mode() {
  local mode_file="${INSTALL_DIR}/.install-mode"
  local existing_mode=""
  [[ ! -L "${INSTALL_DIR}" ]] || die "安装目录不能是符号链接: ${INSTALL_DIR}"
  if [[ -f "${mode_file}" ]]; then
    existing_mode="$(tr -d '\r\n' <"${mode_file}")"
    [[ "${existing_mode}" == "${MODE}" ]] ||
      die "当前目录已使用 ${existing_mode} 模式，不能直接切换为 ${MODE}"
    return
  fi
  if [[ -d "${INSTALL_DIR}" ]] &&
     [[ -n "$(find "${INSTALL_DIR}" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
    die "安装目录非空且缺少模式标记: ${INSTALL_DIR}"
  fi
  install -d -m 0755 "${INSTALL_DIR}"
  printf '%s\n' "${MODE}" >"${mode_file}.tmp"
  chmod 0644 "${mode_file}.tmp"
  mv -f -- "${mode_file}.tmp" "${mode_file}"
}

version_is_less() {
  local left_major left_minor left_patch right_major right_minor right_patch
  IFS=. read -r left_major left_minor left_patch <<<"$1"
  IFS=. read -r right_major right_minor right_patch <<<"$2"
  if (( left_major != right_major )); then
    (( left_major < right_major ))
    return
  fi
  if (( left_minor != right_minor )); then
    (( left_minor < right_minor ))
    return
  fi
  (( left_patch < right_patch ))
}

check_version_transition() {
  local version_file="${INSTALL_DIR}/VERSION"
  local current_version
  [[ -f "${version_file}" ]] || return 0
  current_version="$(tr -d '\r\n' <"${version_file}")"
  [[ "${current_version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
    die "当前 VERSION 文件格式无效"
  [[ "${current_version}" != "${VERSION}" ]] || return 0
  [[ "${VERSION_EXPLICIT}" == "true" ]] ||
    die "检测到已安装 ${current_version}；升级必须显式传入 --version ${VERSION}"
  version_is_less "${VERSION}" "${current_version}" &&
    die "安装器不允许从 ${current_version} 降级到 ${VERSION}"
  info "将 yunlume 从 ${current_version} 升级到 ${VERSION}。"
}

upsert_env() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temporary="${file}.tmp.$$"
  awk -v wanted="${key}" -v replacement="${value}" '
    BEGIN { seen = 0 }
    $0 ~ ("^" wanted "=") {
      if (!seen) print wanted "=" replacement
      seen = 1
      next
    }
    { print }
    END { if (!seen) print wanted "=" replacement }
  ' "${file}" >"${temporary}"
  chmod 0600 "${temporary}"
  mv -f -- "${temporary}" "${file}"
}

write_docker_env() {
  local env_file="$1"
  local backend_image="$2"
  local frontend_image="$3"
  local jwt_secret
  jwt_secret="$(openssl rand -hex 32)"
  cat >"${env_file}" <<EOF
TZ=Asia/Hong_Kong
APP_BIND_ADDRESS=0.0.0.0
APP_PORT=${APP_PORT}
UPLOADS_VOLUME_NAME=yunlume_uploads_data
LOGS_VOLUME_NAME=yunlume_backend_logs
DATABASE_CONFIG_VOLUME_NAME=yunlume_database_config
BACKEND_IMAGE=${backend_image}
FRONTEND_IMAGE=${frontend_image}
JWT_SECRET=${jwt_secret}
NAV_BOOTSTRAP_ENABLED=false
NAV_DEMO_DATA_ENABLED=false
NAV_WEB_INSTALL_ENABLED=true
NAV_DATABASE_SOURCE=UNCONFIGURED
NAV_REDIS_SOURCE=UNCONFIGURED
NAV_ALLOW_INSECURE_DATABASE_SETUP=true
OPENAPI_ENABLED=false
EOF
  chmod 0600 "${env_file}"
}

wait_for_http() {
  local base_url="$1"
  local attempts="${2:-90}"
  local index
  for ((index = 1; index <= attempts; index++)); do
    if curl --fail --silent --show-error --max-time 5 "${base_url}/healthz" >/dev/null 2>&1 &&
       curl --fail --silent --show-error --max-time 5 "${base_url}/api/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_url() {
  local url="$1"
  local attempts="${2:-20}"
  local index
  for ((index = 1; index <= attempts; index++)); do
    if curl --fail --silent --show-error --max-time 5 "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

rollback_docker() {
  local -a compose_command=()
  local rollback_failed="false"
  set +e
  DOCKER_TRANSACTION_ACTIVE="false"
  trap - ERR
  info "Docker 安装未完成，正在恢复上一组运行文件..."
  if [[ "${DOCKER_HAD_ENV}" == "true" ]]; then
    cp -p -- "${DOCKER_ENV_BACKUP}" "${DOCKER_ENV_FILE}" || rollback_failed="true"
  else
    rm -f -- "${DOCKER_ENV_FILE}" || rollback_failed="true"
  fi
  if [[ "${DOCKER_HAD_COMPOSE}" == "true" ]]; then
    cp -p -- "${DOCKER_COMPOSE_BACKUP}" "${DOCKER_COMPOSE_FILE}" || rollback_failed="true"
  else
    rm -f -- "${DOCKER_COMPOSE_FILE}" || rollback_failed="true"
  fi
  if [[ "${DOCKER_HAD_VERSION}" == "true" ]]; then
    cp -p -- "${DOCKER_VERSION_BACKUP}" "${DOCKER_VERSION_FILE}" || rollback_failed="true"
  else
    rm -f -- "${DOCKER_VERSION_FILE}" || rollback_failed="true"
  fi
  if [[ "${DOCKER_HAD_MANIFEST}" == "true" ]]; then
    cp -p -- "${DOCKER_MANIFEST_BACKUP}" "${DOCKER_MANIFEST_FILE}" || rollback_failed="true"
  else
    rm -f -- "${DOCKER_MANIFEST_FILE}" || rollback_failed="true"
  fi
  if [[ "${DOCKER_SERVICES_MUTATED}" == "true" &&
        -f "${DOCKER_ENV_FILE}" && -f "${DOCKER_COMPOSE_FILE}" ]]; then
    compose_command=(
      docker compose
      --project-name yunlume
      --project-directory "${INSTALL_DIR}"
      --env-file "${DOCKER_ENV_FILE}"
      --file "${DOCKER_COMPOSE_FILE}"
    )
    if ! "${compose_command[@]}" up -d --no-build --force-recreate backend frontend; then
      rollback_failed="true"
    elif ! wait_for_http "http://127.0.0.1:${DOCKER_ROLLBACK_PORT}" 20; then
      rollback_failed="true"
    fi
  elif [[ "${DOCKER_SERVICES_MUTATED}" == "true" &&
          -f "${WORK_DIR}/failed.compose.yml" && -f "${WORK_DIR}/failed.env" ]]; then
    if ! docker compose --project-name yunlume \
      --project-directory "${INSTALL_DIR}" \
      --env-file "${WORK_DIR}/failed.env" \
      --file "${WORK_DIR}/failed.compose.yml" down --remove-orphans; then
      rollback_failed="true"
    fi
  fi
  set -e
  [[ "${rollback_failed}" != "true" ]]
}

docker_transaction_failed() {
  local status="$1"
  trap - ERR
  exit "${status}"
}

install_docker() {
  local compose_asset compose_sha backend_image frontend_image
  local compose_download env_file compose_file version_file manifest_target
  local had_existing="false"
  local install_failed="false"
  local backup_env="${WORK_DIR}/previous.env"
  local backup_compose="${WORK_DIR}/previous.compose.yml"
  local backup_version="${WORK_DIR}/previous.VERSION"
  local backup_manifest="${WORK_DIR}/previous.release-manifest.json"

  require_command docker
  require_command openssl
  docker compose version >/dev/null 2>&1 || die "需要 Docker Compose v2"

  compose_asset="${MANIFEST_COMPOSE}"
  compose_sha="${MANIFEST_COMPOSE_SHA256}"
  backend_image="${MANIFEST_BACKEND_IMAGE}"
  frontend_image="${MANIFEST_FRONTEND_IMAGE}"
  validate_asset_name "${compose_asset}"
  [[ "${backend_image}" != *[[:space:]]* && "${frontend_image}" != *[[:space:]]* ]] ||
    die "发行清单中的镜像引用无效"

  compose_download="${WORK_DIR}/${compose_asset}"
  download_file "${RELEASE_ASSET_BASE}/${compose_asset}" "${compose_download}"
  verify_sha256 "${compose_download}" "${compose_sha}"

  ensure_install_mode
  check_version_transition
  env_file="${INSTALL_DIR}/.env"
  compose_file="${INSTALL_DIR}/compose.yml"
  version_file="${INSTALL_DIR}/VERSION"
  manifest_target="${INSTALL_DIR}/release-manifest.json"
  DOCKER_ENV_FILE="${env_file}"
  DOCKER_COMPOSE_FILE="${compose_file}"
  DOCKER_VERSION_FILE="${version_file}"
  DOCKER_MANIFEST_FILE="${manifest_target}"
  DOCKER_ENV_BACKUP="${backup_env}"
  DOCKER_COMPOSE_BACKUP="${backup_compose}"
  DOCKER_VERSION_BACKUP="${backup_version}"
  DOCKER_MANIFEST_BACKUP="${backup_manifest}"
  DOCKER_HAD_ENV="false"
  DOCKER_HAD_COMPOSE="false"
  DOCKER_HAD_VERSION="false"
  DOCKER_HAD_MANIFEST="false"
  DOCKER_SERVICES_MUTATED="false"
  DOCKER_ROLLBACK_PORT="${DEFAULT_PORT}"
  [[ ! -L "${env_file}" && ! -L "${compose_file}" &&
     ! -L "${version_file}" && ! -L "${manifest_target}" ]] ||
    die "Docker 安装器管理的文件不能是符号链接"
  if [[ -f "${env_file}" && -f "${compose_file}" ]]; then
    had_existing="true"
    DOCKER_HAD_ENV="true"
    DOCKER_HAD_COMPOSE="true"
    cp -p -- "${env_file}" "${backup_env}"
    cp -p -- "${compose_file}" "${backup_compose}"
    DOCKER_ROLLBACK_PORT="$(awk -F= '$1 == "APP_PORT" { print $2; exit }' "${backup_env}")"
    [[ "${DOCKER_ROLLBACK_PORT}" =~ ^[0-9]+$ &&
       "${DOCKER_ROLLBACK_PORT}" -ge 1 && "${DOCKER_ROLLBACK_PORT}" -le 65535 ]] ||
      DOCKER_ROLLBACK_PORT="${DEFAULT_PORT}"
  elif [[ -e "${env_file}" || -e "${compose_file}" ]]; then
    die "Docker 安装目录不完整，请同时恢复 .env 与 compose.yml"
  fi
  if [[ -f "${version_file}" ]]; then
    DOCKER_HAD_VERSION="true"
    cp -p -- "${version_file}" "${backup_version}"
  fi
  if [[ -f "${manifest_target}" ]]; then
    DOCKER_HAD_MANIFEST="true"
    cp -p -- "${manifest_target}" "${backup_manifest}"
  fi

  DOCKER_TRANSACTION_ACTIVE="true"
  trap 'docker_transaction_failed $?' ERR
  if [[ "${had_existing}" != "true" ]]; then
    write_docker_env "${env_file}" "${backend_image}" "${frontend_image}"
  fi
  if [[ "${had_existing}" == "true" ]]; then
    upsert_env "${env_file}" BACKEND_IMAGE "${backend_image}"
    upsert_env "${env_file}" FRONTEND_IMAGE "${frontend_image}"
    upsert_env "${env_file}" APP_PORT "${APP_PORT}"
    upsert_env "${env_file}" NAV_ALLOW_INSECURE_DATABASE_SETUP true
  fi
  install -m 0644 "${compose_download}" "${compose_file}.tmp"
  mv -f -- "${compose_file}.tmp" "${compose_file}"
  install -m 0644 "${MANIFEST_FILE}" "${manifest_target}.tmp"
  mv -f -- "${manifest_target}.tmp" "${manifest_target}"
  cp -p -- "${env_file}" "${WORK_DIR}/failed.env"
  cp -p -- "${compose_file}" "${WORK_DIR}/failed.compose.yml"

  local -a compose_command=(
    docker compose
    --project-name yunlume
    --project-directory "${INSTALL_DIR}"
    --env-file "${env_file}"
    --file "${compose_file}"
  )

  info "正在拉取 yunlume 前端和后端镜像..."
  if ! "${compose_command[@]}" pull; then
    install_failed="true"
  else
    DOCKER_SERVICES_MUTATED="true"
    if ! "${compose_command[@]}" up -d --no-build ||
       ! wait_for_http "http://127.0.0.1:${APP_PORT}" 90; then
      install_failed="true"
    fi
  fi
  if [[ "${install_failed}" == "true" ]]; then
    "${compose_command[@]}" ps >&2 || true
    "${compose_command[@]}" logs --tail 80 backend frontend >&2 || true
    die "Docker 镜像拉取、服务启动或健康检查失败"
  fi

  printf '%s\n' "${VERSION}" >"${version_file}.tmp"
  chmod 0644 "${version_file}.tmp"
  mv -f -- "${version_file}.tmp" "${version_file}"
  DOCKER_TRANSACTION_ACTIVE="false"
  trap - ERR
}

java_major_version() {
  "${JAVA_BIN}" -XshowSettings:properties -version 2>&1 |
    awk -F= '/^[[:space:]]*java\.specification\.version[[:space:]]*=/ {
      gsub(/[[:space:]]/, "", $2); print $2; exit
    }'
}

validate_archive_entries() {
  local archive="$1"
  local entry
  while IFS= read -r entry; do
    [[ -n "${entry}" ]] || continue
    [[ "${entry}" != /* && "${entry}" != ".." && "${entry}" != ../* &&
       "${entry}" != *"/../"* && "${entry}" != *"/.." ]] ||
      die "宿主机发行包包含不安全路径: ${entry}"
  done < <(tar -tzf "${archive}")
}

render_template() {
  local source_file="$1"
  local destination="$2"
  local jwt_secret="${3:-}"
  sed \
    -e "s|__APP_PORT__|${APP_PORT}|g" \
    -e "s|__INSTALL_DIR__|${INSTALL_DIR}|g" \
    -e "s|__JAVA_BIN__|${JAVA_BIN}|g" \
    -e "s|__JWT_SECRET__|${jwt_secret}|g" \
    "${source_file}" >"${destination}"
}

ensure_service_user() {
  local nologin_shell
  if ! getent group yunlume >/dev/null 2>&1; then
    groupadd --system yunlume
  fi
  if ! id -u yunlume >/dev/null 2>&1; then
    nologin_shell="$(command -v nologin || true)"
    [[ -n "${nologin_shell}" ]] || nologin_shell="/usr/sbin/nologin"
    useradd --system --gid yunlume --home-dir /var/lib/yunlume \
      --shell "${nologin_shell}" yunlume
  fi
}

rollback_host() {
  local rollback_failed="false"
  set +e
  HOST_TRANSACTION_ACTIVE="false"
  trap - ERR
  info "宿主机版本未通过健康检查，正在恢复上一版本..."
  if [[ "${HOST_BACKEND_MUTATED}" == "true" ]]; then
    systemctl stop yunlume-backend.service || rollback_failed="true"
    if [[ "${HOST_HAD_SERVICE_FILE}" != "true" ]]; then
      systemctl disable yunlume-backend.service >/dev/null 2>&1 || rollback_failed="true"
    fi
  fi
  if [[ "${HOST_NGINX_MUTATED}" == "true" && "${HOST_NGINX_WAS_ACTIVE}" != "true" ]]; then
    systemctl stop nginx.service || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_CURRENT}" == "true" && -d "${HOST_PREVIOUS_CURRENT}" ]]; then
    ln -sfn -- "${HOST_PREVIOUS_CURRENT}" "${HOST_CURRENT_LINK}.rollback" || rollback_failed="true"
    mv -Tf -- "${HOST_CURRENT_LINK}.rollback" "${HOST_CURRENT_LINK}" || rollback_failed="true"
  else
    rm -f -- "${HOST_CURRENT_LINK}" "${HOST_CURRENT_LINK}.rollback" || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_NGINX_CONFIG}" == "true" && -f "${HOST_NGINX_BACKUP}" ]]; then
    cp -p -- "${HOST_NGINX_BACKUP}" "${HOST_NGINX_CONFIG}" || rollback_failed="true"
  else
    rm -f -- "${HOST_NGINX_CONFIG}" || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_SERVICE_FILE}" == "true" && -f "${HOST_SERVICE_BACKUP}" ]]; then
    cp -p -- "${HOST_SERVICE_BACKUP}" "${HOST_SERVICE_FILE}" || rollback_failed="true"
  else
    rm -f -- "${HOST_SERVICE_FILE}" || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_NGINX_LINK}" == "true" ]]; then
    ln -sfn -- "${HOST_PREVIOUS_NGINX_LINK}" "${HOST_NGINX_LINK}" || rollback_failed="true"
  else
    rm -f -- "${HOST_NGINX_LINK}" || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_VERSION}" == "true" && -f "${HOST_VERSION_BACKUP}" ]]; then
    cp -p -- "${HOST_VERSION_BACKUP}" "${HOST_VERSION_FILE}" || rollback_failed="true"
  else
    rm -f -- "${HOST_VERSION_FILE}" || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_MANIFEST}" == "true" && -f "${HOST_MANIFEST_BACKUP}" ]]; then
    cp -p -- "${HOST_MANIFEST_BACKUP}" "${HOST_MANIFEST_FILE}" || rollback_failed="true"
  else
    rm -f -- "${HOST_MANIFEST_FILE}" || rollback_failed="true"
  fi
  if [[ "${HOST_HAD_APP_ENV}" == "true" && -f "${HOST_APP_ENV_BACKUP}" ]]; then
    cp -p -- "${HOST_APP_ENV_BACKUP}" "${HOST_APP_ENV_FILE}" || rollback_failed="true"
  else
    rm -f -- "${HOST_APP_ENV_FILE}" || rollback_failed="true"
  fi
  systemctl daemon-reload || rollback_failed="true"
  if [[ "${HOST_HAD_SERVICE_FILE}" == "true" ]]; then
    if [[ "${HOST_SERVICE_WAS_ENABLED}" == "true" ]]; then
      systemctl enable yunlume-backend.service >/dev/null 2>&1 || rollback_failed="true"
    else
      systemctl disable yunlume-backend.service >/dev/null 2>&1 || rollback_failed="true"
    fi
    if [[ "${HOST_SERVICE_WAS_ACTIVE}" == "true" ]]; then
      if [[ "${HOST_BACKEND_MUTATED}" == "true" ]]; then
        systemctl restart yunlume-backend.service || rollback_failed="true"
      fi
    else
      systemctl stop yunlume-backend.service || rollback_failed="true"
    fi
  fi
  if [[ "${HOST_NGINX_WAS_ENABLED}" == "true" ]]; then
    systemctl enable nginx.service >/dev/null 2>&1 || rollback_failed="true"
  else
    systemctl disable nginx.service >/dev/null 2>&1 || rollback_failed="true"
  fi
  if [[ "${HOST_NGINX_WAS_ACTIVE}" == "true" ]]; then
    if [[ "${HOST_NGINX_MUTATED}" == "true" ]]; then
      if ! nginx -t >/dev/null 2>&1 || ! systemctl reload nginx.service; then
        rollback_failed="true"
      fi
    fi
  else
    systemctl stop nginx.service || rollback_failed="true"
  fi
  if [[ "${HOST_SERVICE_WAS_ACTIVE}" == "true" ]]; then
    if [[ "${HOST_NGINX_WAS_ACTIVE}" == "true" ]]; then
      wait_for_http "http://127.0.0.1:${HOST_ROLLBACK_PORT}" 20 || rollback_failed="true"
    else
      wait_for_url "http://127.0.0.1:18081/api/health" 20 || rollback_failed="true"
    fi
  fi
  set -e
  [[ "${rollback_failed}" != "true" ]]
}

host_transaction_failed() {
  local status="$1"
  trap - ERR
  if [[ "${HOST_TRANSACTION_ACTIVE}" == "true" ]]; then
    journalctl -u yunlume-backend.service -n 80 --no-pager >&2 || true
  fi
  exit "${status}"
}

install_host() {
  local archive_name archive_sha archive_file staging_root package_version
  local release_root release_dir temporary_release current_link
  local env_file jwt_secret nginx_config nginx_link service_file
  local major existing_nginx_target

  require_command java
  require_command nginx
  require_command systemctl
  require_command tar
  require_command cmp
  require_command diff
  require_command getent
  require_command useradd
  require_command groupadd
  require_command head
  require_command openssl
  require_command readlink
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  [[ "${JAVA_BIN}" =~ ^/[A-Za-z0-9._/+:-]+$ && -x "${JAVA_BIN}" ]] ||
    die "无法确定可供 systemd 使用的 Java 绝对路径"
  major="$(java_major_version)"
  [[ "${major}" =~ ^[0-9]+$ && "${major}" -ge 17 ]] || die "宿主机模式需要 Java 17 或更高版本"
  [[ -d /run/systemd/system ]] || die "宿主机模式需要 systemd"
  [[ -d /etc/nginx/conf.d ]] || die "Nginx 必须启用 /etc/nginx/conf.d"

  archive_name="${MANIFEST_HOST_ARCHIVE}"
  archive_sha="${MANIFEST_HOST_ARCHIVE_SHA256}"
  validate_asset_name "${archive_name}"
  archive_file="${WORK_DIR}/${archive_name}"
  download_file "${RELEASE_ASSET_BASE}/${archive_name}" "${archive_file}"
  verify_sha256 "${archive_file}" "${archive_sha}"
  validate_archive_entries "${archive_file}"
  staging_root="${WORK_DIR}/host-package"
  install -d -m 0755 "${staging_root}"
  tar -xzf "${archive_file}" -C "${staging_root}"
  [[ -z "$(find "${staging_root}" -type l -print -quit)" ]] ||
    die "宿主机发行包不能包含符号链接"
  [[ -f "${staging_root}/backend/yunlume-backend.jar" ]] || die "宿主机包缺少后端 JAR"
  [[ -f "${staging_root}/frontend/index.html" ]] || die "宿主机包缺少前端文件"
  [[ -f "${staging_root}/deploy/app.env.template" ]] || die "宿主机包缺少环境模板"
  [[ -f "${staging_root}/deploy/yunlume-backend.service" ]] || die "宿主机包缺少 systemd 模板"
  [[ -f "${staging_root}/deploy/yunlume.nginx.conf" ]] || die "宿主机包缺少 Nginx 模板"
  [[ -f "${staging_root}/SHA256SUMS" ]] || die "宿主机包缺少内部校验文件"
  (cd "${staging_root}" && sha256sum --check --quiet --strict SHA256SUMS) ||
    die "宿主机包内部文件校验失败"
  package_version="$(tr -d '\r\n' <"${staging_root}/VERSION")"
  [[ "${package_version}" == "${VERSION}" ]] || die "宿主机包版本与发行清单不一致"

  ensure_install_mode
  check_version_transition
  ensure_service_user
  [[ ! -L "${INSTALL_DIR}/releases" ]] || die "releases 目录不能是符号链接"
  [[ ! -L /etc/yunlume ]] || die "/etc/yunlume 不能是符号链接"
  [[ ! -L /var/lib/yunlume ]] || die "/var/lib/yunlume 不能是符号链接"
  [[ ! -L /var/lib/yunlume/config ]] || die "/var/lib/yunlume/config 不能是符号链接"
  [[ ! -L /var/lib/yunlume/uploads ]] || die "/var/lib/yunlume/uploads 不能是符号链接"
  install -d -m 0755 "${INSTALL_DIR}/releases" /etc/yunlume
  install -d -m 0755 -o yunlume -g yunlume /var/lib/yunlume
  install -d -m 0700 -o yunlume -g yunlume /var/lib/yunlume/config
  install -d -m 0755 -o yunlume -g yunlume /var/lib/yunlume/uploads

  release_root="${INSTALL_DIR}/releases"
  release_dir="${release_root}/${VERSION}"
  [[ ! -L "${release_dir}" ]] || die "版本目录不能是符号链接: ${release_dir}"
  if [[ -e "${release_dir}" && ! -d "${release_dir}" ]]; then
    die "版本路径已存在但不是目录: ${release_dir}"
  elif [[ -d "${release_dir}" ]]; then
    [[ -f "${release_dir}/backend/yunlume-backend.jar" &&
       -f "${release_dir}/frontend/index.html" &&
       -f "${release_dir}/.archive-sha256" ]] ||
      die "现有版本目录不完整: ${release_dir}"
    [[ -z "$(find "${release_dir}" -type l -print -quit)" ]] ||
      die "现有版本目录不能包含符号链接: ${release_dir}"
    [[ "$(tr -d '\r\n' <"${release_dir}/.archive-sha256")" == "${archive_sha,,}" ]] ||
      die "现有版本目录与发行包摘要不一致: ${release_dir}"
    cmp --silent "${staging_root}/VERSION" "${release_dir}/VERSION" ||
      die "现有版本目录的 VERSION 已改变: ${release_dir}"
    diff --brief --recursive "${staging_root}/backend" "${release_dir}/backend" >/dev/null ||
      die "现有版本目录的后端文件与发行包不一致: ${release_dir}"
    diff --brief --recursive "${staging_root}/frontend" "${release_dir}/frontend" >/dev/null ||
      die "现有版本目录的前端文件与发行包不一致: ${release_dir}"
  else
    temporary_release="${release_dir}.tmp.$$"
    HOST_TEMPORARY_RELEASE="${temporary_release}"
    install -d -m 0755 "${temporary_release}/backend" "${temporary_release}/frontend"
    install -m 0644 "${staging_root}/backend/yunlume-backend.jar" \
      "${temporary_release}/backend/yunlume-backend.jar"
    cp -a -- "${staging_root}/frontend/." "${temporary_release}/frontend/"
    printf '%s\n' "${VERSION}" >"${temporary_release}/VERSION"
    printf '%s\n' "${archive_sha,,}" >"${temporary_release}/.archive-sha256"
    chown -R root:root "${temporary_release}"
    mv -- "${temporary_release}" "${release_dir}"
    HOST_TEMPORARY_RELEASE=""
  fi

  env_file="/etc/yunlume/app.env"
  current_link="${INSTALL_DIR}/current"
  nginx_config="/etc/yunlume/nginx.conf"
  nginx_link="/etc/nginx/conf.d/yunlume.conf"
  service_file="/etc/systemd/system/yunlume-backend.service"
  HOST_CURRENT_LINK="${current_link}"
  HOST_NGINX_CONFIG="${nginx_config}"
  HOST_NGINX_LINK="${nginx_link}"
  HOST_SERVICE_FILE="${service_file}"
  HOST_NGINX_BACKUP="${WORK_DIR}/previous.nginx.conf"
  HOST_SERVICE_BACKUP="${WORK_DIR}/previous.service"
  HOST_VERSION_FILE="${INSTALL_DIR}/VERSION"
  HOST_MANIFEST_FILE="${INSTALL_DIR}/release-manifest.json"
  HOST_VERSION_BACKUP="${WORK_DIR}/previous.VERSION"
  HOST_MANIFEST_BACKUP="${WORK_DIR}/previous.release-manifest.json"
  HOST_APP_ENV_FILE="${env_file}"
  HOST_APP_ENV_BACKUP="${WORK_DIR}/previous.app.env"
  HOST_HAD_CURRENT="false"
  HOST_HAD_NGINX_CONFIG="false"
  HOST_HAD_SERVICE_FILE="false"
  HOST_HAD_NGINX_LINK="false"
  HOST_SERVICE_WAS_ENABLED="false"
  HOST_SERVICE_WAS_ACTIVE="false"
  HOST_NGINX_WAS_ACTIVE="false"
  HOST_HAD_VERSION="false"
  HOST_HAD_MANIFEST="false"
  HOST_HAD_APP_ENV="false"
  HOST_NGINX_WAS_ENABLED="false"
  HOST_BACKEND_MUTATED="false"
  HOST_NGINX_MUTATED="false"
  HOST_ROLLBACK_PORT="${DEFAULT_PORT}"
  if [[ -L "${current_link}" ]]; then
    HOST_PREVIOUS_CURRENT="$(readlink -f "${current_link}" || true)"
    [[ -n "${HOST_PREVIOUS_CURRENT}" && -d "${HOST_PREVIOUS_CURRENT}" ]] ||
      die "current 指向无效版本目录"
    [[ "${HOST_PREVIOUS_CURRENT}" == "${release_root}/"* ]] ||
      die "current 必须指向 ${release_root} 内的版本目录"
    HOST_HAD_CURRENT="true"
  elif [[ -e "${current_link}" ]]; then
    die "current 必须是由安装器管理的符号链接"
  fi
  [[ ! -L "${nginx_config}" ]] || die "${nginx_config} 不能是符号链接"
  [[ ! -L "${service_file}" ]] || die "${service_file} 不能是符号链接"
  [[ ! -L "${env_file}" ]] || die "${env_file} 不能是符号链接"
  if [[ -f "${nginx_config}" ]]; then
    HOST_HAD_NGINX_CONFIG="true"
    cp -p -- "${nginx_config}" "${HOST_NGINX_BACKUP}"
    HOST_ROLLBACK_PORT="$(sed -n \
      's/^[[:space:]]*listen[[:space:]]\+0\.0\.0\.0:\([0-9]\+\);.*/\1/p' \
      "${HOST_NGINX_BACKUP}" | head -n 1)"
    [[ "${HOST_ROLLBACK_PORT}" =~ ^[0-9]+$ &&
       "${HOST_ROLLBACK_PORT}" -ge 1 && "${HOST_ROLLBACK_PORT}" -le 65535 ]] ||
      HOST_ROLLBACK_PORT="${DEFAULT_PORT}"
  fi
  if [[ -f "${service_file}" ]]; then
    HOST_HAD_SERVICE_FILE="true"
    cp -p -- "${service_file}" "${HOST_SERVICE_BACKUP}"
  fi
  if [[ -L "${nginx_link}" ]]; then
    existing_nginx_target="$(readlink -f "${nginx_link}" || true)"
    [[ "${existing_nginx_target}" == "${nginx_config}" ]] ||
      die "${nginx_link} 未指向 yunlume 管理的配置"
    HOST_HAD_NGINX_LINK="true"
    HOST_PREVIOUS_NGINX_LINK="$(readlink "${nginx_link}")"
  elif [[ -e "${nginx_link}" ]]; then
    die "${nginx_link} 已存在且不由 yunlume 管理"
  fi
  if [[ -f "${HOST_VERSION_FILE}" ]]; then
    HOST_HAD_VERSION="true"
    cp -p -- "${HOST_VERSION_FILE}" "${HOST_VERSION_BACKUP}"
  fi
  if [[ -f "${HOST_MANIFEST_FILE}" ]]; then
    HOST_HAD_MANIFEST="true"
    cp -p -- "${HOST_MANIFEST_FILE}" "${HOST_MANIFEST_BACKUP}"
  fi
  if [[ -f "${env_file}" ]]; then
    HOST_HAD_APP_ENV="true"
    cp -p -- "${env_file}" "${HOST_APP_ENV_BACKUP}"
  fi
  systemctl is-enabled yunlume-backend.service >/dev/null 2>&1 &&
    HOST_SERVICE_WAS_ENABLED="true"
  systemctl is-active yunlume-backend.service >/dev/null 2>&1 &&
    HOST_SERVICE_WAS_ACTIVE="true"
  systemctl is-active nginx.service >/dev/null 2>&1 && HOST_NGINX_WAS_ACTIVE="true"
  systemctl is-enabled nginx.service >/dev/null 2>&1 && HOST_NGINX_WAS_ENABLED="true"

  HOST_TRANSACTION_ACTIVE="true"
  trap 'host_transaction_failed $?' ERR
  if [[ "${HOST_HAD_APP_ENV}" == "true" ]]; then
    upsert_env "${env_file}" NAV_ALLOW_INSECURE_DATABASE_SETUP true
    upsert_env "${env_file}" CORS_ALLOWED_ORIGINS \
      "http://localhost:${APP_PORT},http://127.0.0.1:${APP_PORT}"
  else
    jwt_secret="$(openssl rand -hex 32)"
    render_template "${staging_root}/deploy/app.env.template" "${env_file}.tmp" "${jwt_secret}"
    chmod 0600 "${env_file}.tmp"
    mv -f -- "${env_file}.tmp" "${env_file}"
  fi
  ln -sfn -- "${release_dir}" "${current_link}.new"
  mv -Tf -- "${current_link}.new" "${current_link}"
  render_template "${staging_root}/deploy/yunlume.nginx.conf" "${nginx_config}.tmp"
  install -m 0644 "${nginx_config}.tmp" "${nginx_config}"
  rm -f -- "${nginx_config}.tmp"
  render_template "${staging_root}/deploy/yunlume-backend.service" "${service_file}.tmp"
  install -m 0644 "${service_file}.tmp" "${service_file}"
  rm -f -- "${service_file}.tmp"
  ln -sfn -- "${nginx_config}" "${nginx_link}"

  nginx -t
  systemctl daemon-reload
  HOST_BACKEND_MUTATED="true"
  systemctl enable yunlume-backend.service >/dev/null
  systemctl restart yunlume-backend.service
  HOST_NGINX_MUTATED="true"
  systemctl enable --now nginx.service >/dev/null
  systemctl reload nginx.service
  wait_for_http "http://127.0.0.1:${APP_PORT}" 90

  printf '%s\n' "${VERSION}" >"${INSTALL_DIR}/VERSION.tmp"
  chmod 0644 "${INSTALL_DIR}/VERSION.tmp"
  mv -f -- "${INSTALL_DIR}/VERSION.tmp" "${INSTALL_DIR}/VERSION"
  install -m 0644 "${MANIFEST_FILE}" "${INSTALL_DIR}/release-manifest.json.tmp"
  mv -f -- "${INSTALL_DIR}/release-manifest.json.tmp" "${INSTALL_DIR}/release-manifest.json"
  HOST_TRANSACTION_ACTIVE="false"
  trap - ERR
}

main() {
  parse_args "$@"
  validate_args
  [[ "$(uname -s)" == "Linux" ]] || die "安装器仅支持 Linux"
  validate_docker_architecture
  [[ "$(id -u)" -eq 0 ]] || die "请使用 root 或 sudo 执行安装器"
  require_command curl
  require_command sha256sum
  require_command sed
  require_command awk
  require_command python3
  require_command flock
  require_command find
  require_command mktemp
  require_command stat
  require_command readlink
  validate_install_directory_boundary
  WORK_DIR="$(mktemp -d -t yunlume-install.XXXXXXXX)"
  acquire_lock
  load_manifest
  info "准备安装 yunlume ${VERSION}（${MODE} 模式）..."
  if [[ "${MODE}" == "docker" ]]; then
    install_docker
  else
    install_host
  fi
  info "yunlume ${VERSION} 已启动。"
  print_access_url
}

main "$@"
