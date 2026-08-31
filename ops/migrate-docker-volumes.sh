#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly DEFAULT_UPLOADS_DESTINATION="yunlume_uploads_data"
readonly DEFAULT_CONFIG_DESTINATION="yunlume_database_config"
readonly OPERATIONS_LOCK="/run/lock/yunlume-operations.lock"

SOURCE_UPLOADS=""
SOURCE_CONFIG=""
DESTINATION_UPLOADS="${DEFAULT_UPLOADS_DESTINATION}"
DESTINATION_CONFIG="${DEFAULT_CONFIG_DESTINATION}"
HELPER_IMAGE=""
REPORT_DIR="/var/lib/yunlume/migrations"
EXECUTE="false"
RUN_ID=""
HELPER_IMAGE_ID=""
CREATED_UPLOADS="false"
CREATED_CONFIG="false"
REPORT_TEMP=""

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

finish() {
  local status=$?
  trap - EXIT
  set +e
  if [[ "${status}" -ne 0 &&
        ( "${CREATED_UPLOADS}" == "true" || "${CREATED_CONFIG}" == "true" ) ]]; then
    printf '迁移未完成；已创建的目标卷不会自动删除，请按 run 标签检查后再处理。\n' >&2
  fi
  if [[ -n "${REPORT_TEMP}" && -f "${REPORT_TEMP}" && ! -L "${REPORT_TEMP}" ]]; then
    rm -f -- "${REPORT_TEMP}"
  fi
  exit "${status}"
}

trap finish EXIT

usage() {
  cat <<'EOF'
用法:
  migrate-docker-volumes.sh \
    --source-uploads-volume <卷名> \
    --source-config-volume <卷名> \
    --helper-image <本机已有的 Linux 镜像> \
    [--destination-uploads-volume yunlume_uploads_data] \
    [--destination-config-volume yunlume_database_config] \
    [--report-dir /var/lib/yunlume/migrations] \
    --execute

说明:
  只复制上传卷和安装配置卷，不删除源卷，也不迁移外部 PostgreSQL 或 Redis。
  源卷不能被运行中的容器挂载。helper 镜像只从本机读取，不会自动拉取。
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

assert_secure_root_directory_chain() {
  local target="$1"
  local current="/"
  local component owner mode
  local -a path_parts=()
  IFS=/ read -r -a path_parts <<<"${target#/}"
  for component in "${path_parts[@]}"; do
    [[ -n "${component}" ]] || continue
    current="${current%/}/${component}"
    [[ -d "${current}" && ! -L "${current}" ]] ||
      die "报告目录链必须全部由真实目录组成: ${current}"
    owner="$(stat -c '%u' "${current}")"
    [[ "${owner}" == "0" ]] || die "报告目录链必须全部属于 root: ${current}"
    mode="$(stat -c '%a' "${current}")"
    (( (8#${mode} & 8#022) == 0 )) ||
      die "报告目录链不能包含组或其他用户可写目录: ${current}"
  done
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --source-uploads-volume)
        [[ $# -ge 2 ]] || die "$1 缺少参数"
        SOURCE_UPLOADS="$2"
        shift 2
        ;;
      --source-config-volume)
        [[ $# -ge 2 ]] || die "$1 缺少参数"
        SOURCE_CONFIG="$2"
        shift 2
        ;;
      --destination-uploads-volume)
        [[ $# -ge 2 ]] || die "$1 缺少参数"
        DESTINATION_UPLOADS="$2"
        shift 2
        ;;
      --destination-config-volume)
        [[ $# -ge 2 ]] || die "$1 缺少参数"
        DESTINATION_CONFIG="$2"
        shift 2
        ;;
      --helper-image)
        [[ $# -ge 2 ]] || die "$1 缺少参数"
        HELPER_IMAGE="$2"
        shift 2
        ;;
      --report-dir)
        [[ $# -ge 2 ]] || die "$1 缺少参数"
        REPORT_DIR="${2%/}"
        shift 2
        ;;
      --execute)
        EXECUTE="true"
        shift
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

validate_volume_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "Docker 卷名无效: $1"
}

validate_args() {
  [[ -n "${SOURCE_UPLOADS}" ]] || die "必须提供 --source-uploads-volume"
  [[ -n "${SOURCE_CONFIG}" ]] || die "必须提供 --source-config-volume"
  [[ -n "${HELPER_IMAGE}" ]] || die "必须提供 --helper-image"
  [[ "${EXECUTE}" == "true" ]] || die "确认源应用已停止后，显式传入 --execute"
  validate_volume_name "${SOURCE_UPLOADS}"
  validate_volume_name "${SOURCE_CONFIG}"
  validate_volume_name "${DESTINATION_UPLOADS}"
  validate_volume_name "${DESTINATION_CONFIG}"
  [[ "${SOURCE_UPLOADS}" != "${DESTINATION_UPLOADS}" ]] || die "上传源卷和目标卷不能相同"
  [[ "${SOURCE_CONFIG}" != "${DESTINATION_CONFIG}" ]] || die "配置源卷和目标卷不能相同"
  [[ "${SOURCE_UPLOADS}" != "${SOURCE_CONFIG}" ]] || die "上传源卷和配置源卷不能相同"
  [[ "${SOURCE_UPLOADS}" != "${DESTINATION_CONFIG}" ]] || die "上传源卷不能同时作为配置目标卷"
  [[ "${SOURCE_CONFIG}" != "${DESTINATION_UPLOADS}" ]] || die "配置源卷不能同时作为上传目标卷"
  [[ "${DESTINATION_UPLOADS}" != "${DESTINATION_CONFIG}" ]] || die "两个目标卷不能相同"
  [[ "${REPORT_DIR}" =~ ^/[A-Za-z0-9._/-]+$ && "${REPORT_DIR}" != "/" ]] ||
    die "--report-dir 必须是受限字符组成的绝对路径"
  [[ "${REPORT_DIR}" != *"//"* && "${REPORT_DIR}" != *"/./"* &&
     "${REPORT_DIR}" != */. && "${REPORT_DIR}" != *"/../"* &&
     "${REPORT_DIR}" != */.. ]] || die "--report-dir 不能包含空路径段、. 或 .."
  [[ "${HELPER_IMAGE}" != *$'\n'* && "${HELPER_IMAGE}" != *$'\r'* ]] ||
    die "helper 镜像引用无效"
}

acquire_operations_lock() {
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

assert_source_ready() {
  local volume="$1"
  local running_containers
  docker volume inspect "${volume}" >/dev/null 2>&1 || die "源卷不存在: ${volume}"
  if ! running_containers="$(docker ps --quiet --filter "volume=${volume}")"; then
    die "无法检查源卷的运行容器: ${volume}"
  fi
  [[ -z "${running_containers}" ]] ||
    die "源卷仍被运行中的容器挂载，请先停止应用写入: ${volume}"
}

assert_destination_absent() {
  if docker volume inspect "$1" >/dev/null 2>&1; then
    die "目标卷已存在，拒绝合并或覆盖: $1"
  fi
}

create_destination() {
  local destination="$1"
  local source="$2"
  local created label
  created="$(docker volume create \
    --label "com.yunlume.migration.run=${RUN_ID}" \
    --label "com.yunlume.migration.source=${source}" \
    "${destination}")"
  if [[ "${destination}" == "${DESTINATION_UPLOADS}" ]]; then
    CREATED_UPLOADS="true"
  else
    CREATED_CONFIG="true"
  fi
  [[ "${created}" == "${destination}" ]] || die "Docker 返回了意外的目标卷名"
  label="$(docker volume inspect \
    --format '{{ index .Labels "com.yunlume.migration.run" }}' "${destination}")"
  [[ "${label}" == "${RUN_ID}" ]] ||
    die "目标卷可能由并发任务创建，拒绝继续: ${destination}"
}

copy_and_verify() {
  local source="$1"
  local destination="$2"
  docker run --rm \
    --network none \
    --read-only \
    --cap-drop ALL \
    --cap-add CHOWN \
    --cap-add DAC_OVERRIDE \
    --cap-add FOWNER \
    --security-opt no-new-privileges \
    --user 0:0 \
    --mount "type=volume,src=${source},dst=/source,readonly,volume-nocopy" \
    --mount "type=volume,src=${destination},dst=/destination,volume-nocopy" \
    --entrypoint /bin/sh \
    "${HELPER_IMAGE_ID}" -eu -c '
      set -o pipefail
      for command_name in tar find wc awk sort sha256sum diff stat; do
        command -v "$command_name" >/dev/null 2>&1 || {
          printf "helper image is missing command: %s\n" "$command_name" >&2
          exit 40
        }
      done
      [ -z "$(find /destination -mindepth 1 -print -quit)" ] || {
        printf "destination volume is not empty\n" >&2
        exit 41
      }
      [ -z "$(find /source ! -type d ! -type f -print -quit)" ] || {
        printf "source volume contains a symlink or special file\n" >&2
        exit 42
      }
      tar -C /source -cf - . | tar -C /destination -xpf -
      diff -qr /source /destination >/dev/null

      volume_stats() {
        root="$1"
        entries="$(find "$root" -mindepth 1 | wc -l | awk "{print \$1}")"
        files="$(find "$root" -type f | wc -l | awk "{print \$1}")"
        bytes="$(find "$root" -type f -exec wc -c {} \; | awk "{sum += \$1} END {print sum + 0}")"
        digest="$(cd "$root" && find . -type f -exec sha256sum {} \; | LC_ALL=C sort | sha256sum | awk "{print \$1}")"
        metadata="$(
          cd "$root"
          {
            printf "ROOT|"
            stat -c "%a|%u|%g" .
            find . -mindepth 1 -type d -exec stat -c "D|%a|%u|%g|%n" {} \;
            find . -type f -exec stat -c "F|%a|%u|%g|%s|%n" {} \;
          } | LC_ALL=C sort | sha256sum | awk "{print \$1}"
        )"
        printf "%s|%s|%s|%s|%s" "$entries" "$files" "$bytes" "$digest" "$metadata"
      }

      source_stats="$(volume_stats /source)"
      destination_stats="$(volume_stats /destination)"
      printf "source=%s\ndestination=%s\n" "$source_stats" "$destination_stats"
      [ "$source_stats" = "$destination_stats" ] || exit 43
    '
}

main() {
  local uploads_metrics config_metrics report_file report_mode
  parse_args "$@"
  validate_args
  [[ "$(uname -s)" == "Linux" ]] || die "迁移脚本仅支持 Linux"
  [[ "$(id -u)" -eq 0 ]] || die "请使用 root 或 sudo 执行迁移脚本"
  require_command docker
  require_command flock
  require_command install
  require_command stat
  require_command date
  require_command mktemp
  require_command readlink
  require_command chmod
  require_command mv
  require_command rm
  acquire_operations_lock
  docker info >/dev/null 2>&1 || die "Docker daemon 不可用"
  HELPER_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "${HELPER_IMAGE}" 2>/dev/null)" ||
    die "本机不存在 helper 镜像，脚本不会自动拉取: ${HELPER_IMAGE}"
  [[ "${HELPER_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]] || die "无法解析 helper 镜像 ID"
  docker run --rm --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges --entrypoint /bin/sh \
    "${HELPER_IMAGE_ID}" -eu -c '
      set -o pipefail
      for command_name in tar find wc awk sort sha256sum diff stat; do
        command -v "$command_name" >/dev/null 2>&1 || exit 40
      done
    ' || die "helper 镜像缺少迁移所需的 Shell 工具"
  RUN_ID="$(date -u +'%Y%m%dT%H%M%SZ')-$$"
  assert_source_ready "${SOURCE_UPLOADS}"
  assert_source_ready "${SOURCE_CONFIG}"
  assert_destination_absent "${DESTINATION_UPLOADS}"
  assert_destination_absent "${DESTINATION_CONFIG}"

  create_destination "${DESTINATION_UPLOADS}" "${SOURCE_UPLOADS}"
  create_destination "${DESTINATION_CONFIG}" "${SOURCE_CONFIG}"
  info "正在复制并校验上传卷..."
  assert_source_ready "${SOURCE_UPLOADS}"
  uploads_metrics="$(copy_and_verify "${SOURCE_UPLOADS}" "${DESTINATION_UPLOADS}")"
  assert_source_ready "${SOURCE_UPLOADS}"
  info "正在复制并校验安装配置卷..."
  assert_source_ready "${SOURCE_CONFIG}"
  config_metrics="$(copy_and_verify "${SOURCE_CONFIG}" "${DESTINATION_CONFIG}")"
  assert_source_ready "${SOURCE_CONFIG}"

  [[ ! -L "${REPORT_DIR}" ]] || die "报告目录不能是符号链接"
  install -d -m 0700 "${REPORT_DIR}"
  [[ "$(readlink -f -- "${REPORT_DIR}")" == "${REPORT_DIR}" ]] ||
    die "报告目录必须是规范路径且不能经过符号链接"
  assert_secure_root_directory_chain "${REPORT_DIR}"
  report_mode="$(stat -c '%a' "${REPORT_DIR}")"
  (( (8#${report_mode} & 8#077) == 0 )) || die "报告目录权限不能开放给组或其他用户"
  report_file="${REPORT_DIR}/${RUN_ID}.report"
  [[ ! -e "${report_file}" ]] || die "迁移报告已存在，拒绝覆盖"
  REPORT_TEMP="$(mktemp "${REPORT_DIR}/.${RUN_ID}.XXXXXXXX")"
  {
    printf 'run_id=%s\n' "${RUN_ID}"
    printf 'helper_image=%s\nhelper_image_id=%s\n' "${HELPER_IMAGE}" "${HELPER_IMAGE_ID}"
    printf 'source_uploads=%s\ndestination_uploads=%s\n' \
      "${SOURCE_UPLOADS}" "${DESTINATION_UPLOADS}"
    printf '%s\n' "${uploads_metrics}"
    printf 'source_config=%s\ndestination_config=%s\n' \
      "${SOURCE_CONFIG}" "${DESTINATION_CONFIG}"
    printf '%s\n' "${config_metrics}"
    printf 'source_volumes_preserved=true\nexternal_services_changed=false\n'
  } >"${REPORT_TEMP}"
  chmod 0600 "${REPORT_TEMP}"
  mv -- "${REPORT_TEMP}" "${report_file}"
  REPORT_TEMP=""

  info "数据卷复制与校验完成，原卷保持不变。"
  info "迁移报告: ${report_file}"
  info "新部署应设置 UPLOADS_VOLUME_NAME=${DESTINATION_UPLOADS}"
  info "新部署应设置 DATABASE_CONFIG_VOLUME_NAME=${DESTINATION_CONFIG}"
  info "启动新 Compose 后，请验证健康状态、登录、上传文件以及安装状态为 COMPLETED。"
}

main "$@"
