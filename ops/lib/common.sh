#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly OPS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly PROJECT_DIR="$(cd -- "${OPS_DIR}/.." && pwd -P)"
readonly COMPOSE_PROJECT_NAME="yunlume"
readonly ENV_FILE="${ENV_FILE:-${PROJECT_DIR}/.env}"
readonly OPERATIONS_LOCK="/run/lock/yunlume-operations.lock"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

assert_project_directory() {
  [[ "${PROJECT_DIR}" != "/" ]] || die "拒绝在文件系统根目录执行"
  [[ -f "${PROJECT_DIR}/docker-compose.yml" ]] || die "缺少 docker-compose.yml"
  [[ -f "${ENV_FILE}" ]] || die "缺少运行环境文件: ${ENV_FILE}"
}

load_environment() {
  local mode raw line key value
  mode="$(stat -c '%a' "${ENV_FILE}")"
  (( (8#${mode} & 8#077) == 0 )) || die "${ENV_FILE} 权限过宽，应为 600"

  # Compose dotenv files are not shell scripts. Parse only the values used by
  # release rollback and never execute the file contents.
  while IFS= read -r raw || [[ -n "${raw}" ]]; do
    line="${raw%$'\r'}"
    [[ "${line}" =~ ^[[:space:]]*$ || "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] ||
      die "${ENV_FILE} 包含无法解析的环境变量行"
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ "${value}" =~ ^\"(.*)\"$ ]]; then
      value="${BASH_REMATCH[1]}"
    elif [[ "${value}" =~ ^\'(.*)\'$ ]]; then
      value="${BASH_REMATCH[1]}"
    fi
    case "${key}" in
      BACKEND_IMAGE|FRONTEND_IMAGE|APP_BIND_ADDRESS|APP_PORT)
        printf -v "${key}" '%s' "${value}"
        export "${key}"
        ;;
    esac
  done <"${ENV_FILE}"
}

acquire_operations_lock() {
  require_command flock
  local lock_dir
  lock_dir="$(dirname -- "${OPERATIONS_LOCK}")"
  [[ ! -L "${lock_dir}" ]] || die "操作锁目录不能是符号链接"
  install -d -m 0755 "${lock_dir}"
  [[ "$(stat -c '%u' "${lock_dir}")" == "0" ]] || die "操作锁目录必须属于 root"
  [[ ! -L "${OPERATIONS_LOCK}" ]] || die "操作锁文件不能是符号链接"
  [[ ! -e "${OPERATIONS_LOCK}" || -f "${OPERATIONS_LOCK}" ]] || die "操作锁文件必须是普通文件"
  exec 9>"${OPERATIONS_LOCK}"
  flock -n 9 || die "已有 yunlume 操作正在运行"
}

compose() {
  docker compose \
    --project-name "${COMPOSE_PROJECT_NAME}" \
    --project-directory "${PROJECT_DIR}" \
    --env-file "${ENV_FILE}" \
    --file "${PROJECT_DIR}/docker-compose.yml" \
    "$@"
}
