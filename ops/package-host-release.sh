#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly HOST_TEMPLATE_DIR="${PROJECT_DIR}/deploy/host"
readonly REQUIRED_HOST_TEMPLATES=(
  "app.env.template"
  "yunlume-backend.service"
  "yunlume.nginx.conf"
)

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '用法: %s <X.Y.Z>\n' "${0##*/}" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

project_path() {
  local value="$1"
  if [[ "${value}" == /* ]]; then
    printf '%s\n' "${value}"
  else
    printf '%s/%s\n' "${PROJECT_DIR}" "${value}"
  fi
}

cleanup() {
  if [[ "${PAIR_COMMITTED:-false}" != "true" ]]; then
    if [[ "${ARCHIVE_PUBLISHED:-false}" == "true" ]]; then
      rm -f -- "${FINAL_ARCHIVE}"
    fi
    if [[ "${CHECKSUM_PUBLISHED:-false}" == "true" ]]; then
      rm -f -- "${FINAL_CHECKSUM}"
    fi
  fi
  if [[ -n "${STAGING_DIR:-}" && -d "${STAGING_DIR}" ]]; then
    rm -rf -- "${STAGING_DIR}"
  fi
  rm -f -- "${TEMP_ARCHIVE:-}" "${TEMP_CHECKSUM:-}"
}

[[ $# -eq 1 ]] || {
  usage
  exit 2
}

readonly VERSION="$1"
[[ "${VERSION}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
  die "版本必须是不带 v、无前导零的 X.Y.Z"

require_command cp
require_command find
require_command ln
require_command mktemp
require_command mv
require_command sha256sum
require_command sort
require_command tar

if [[ -n "${BACKEND_JAR:-}" ]]; then
  backend_jar="$(project_path "${BACKEND_JAR}")"
else
  shopt -s nullglob
  backend_candidates=("${PROJECT_DIR}"/nav-backend/target/*.jar)
  shopt -u nullglob
  (( ${#backend_candidates[@]} == 1 )) ||
    die "默认路径必须且只能存在一个 nav-backend/target/*.jar"
  backend_jar="${backend_candidates[0]}"
fi

frontend_dist="$(project_path "${FRONTEND_DIST:-nav-frontend/dist}")"
output_dir="$(project_path "${OUTPUT_DIR:-release}")"

[[ -f "${backend_jar}" && ! -L "${backend_jar}" ]] ||
  die "后端 JAR 不存在、不是普通文件或是符号链接: ${backend_jar}"
[[ "${backend_jar}" == *.jar ]] || die "后端产物必须使用 .jar 扩展名"
[[ -s "${backend_jar}" ]] || die "后端 JAR 不能为空"

[[ -d "${frontend_dist}" && ! -L "${frontend_dist}" ]] ||
  die "前端 dist 不存在、不是目录或是符号链接: ${frontend_dist}"
[[ -f "${frontend_dist}/index.html" && -s "${frontend_dist}/index.html" &&
   ! -L "${frontend_dist}/index.html" ]] ||
  die "前端 dist 缺少非空的普通 index.html"
[[ -z "$(find "${frontend_dist}" -type l -print -quit)" ]] ||
  die "前端 dist 不允许包含符号链接"
[[ -z "$(find "${frontend_dist}" ! -type d ! -type f -print -quit)" ]] ||
  die "前端 dist 只能包含普通文件和目录"

[[ -d "${HOST_TEMPLATE_DIR}" && ! -L "${HOST_TEMPLATE_DIR}" ]] ||
  die "缺少 deploy/host 模板目录"
shopt -s nullglob dotglob
host_template_entries=("${HOST_TEMPLATE_DIR}"/*)
shopt -u nullglob dotglob
(( ${#host_template_entries[@]} == ${#REQUIRED_HOST_TEMPLATES[@]} )) ||
  die "deploy/host 必须且只能包含三个发行模板"
for template in "${REQUIRED_HOST_TEMPLATES[@]}"; do
  template_path="${HOST_TEMPLATE_DIR}/${template}"
  [[ -f "${template_path}" && ! -L "${template_path}" && -s "${template_path}" ]] ||
    die "缺少非空普通模板或模板是符号链接: deploy/host/${template}"
done

if [[ -e "${output_dir}" ]]; then
  [[ -d "${output_dir}" && ! -L "${output_dir}" ]] ||
    die "OUTPUT_DIR 不是普通目录或是符号链接: ${output_dir}"
else
  mkdir -p -- "${output_dir}"
fi
[[ -w "${output_dir}" ]] || die "OUTPUT_DIR 不可写: ${output_dir}"

readonly ARCHIVE_NAME="yunlume-host-v${VERSION}.tar.gz"
readonly CHECKSUM_NAME="${ARCHIVE_NAME}.sha256"
readonly FINAL_ARCHIVE="${output_dir}/${ARCHIVE_NAME}"
readonly FINAL_CHECKSUM="${output_dir}/${CHECKSUM_NAME}"
readonly TEMP_ARCHIVE="${output_dir}/.${ARCHIVE_NAME}.$$"
readonly TEMP_CHECKSUM="${output_dir}/.${CHECKSUM_NAME}.$$"
PAIR_COMMITTED="false"
ARCHIVE_PUBLISHED="false"
CHECKSUM_PUBLISHED="false"
[[ ! -e "${FINAL_ARCHIVE}" && ! -e "${FINAL_CHECKSUM}" ]] ||
  die "目标发行文件已存在，拒绝覆盖: ${ARCHIVE_NAME}"
[[ ! -e "${TEMP_ARCHIVE}" && ! -e "${TEMP_CHECKSUM}" ]] ||
  die "临时发行文件已存在，请稍后重试"

STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yunlume-host-release.XXXXXXXXXX")"
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
readonly PACKAGE_DIR="${STAGING_DIR}/package"
mkdir -p -- "${PACKAGE_DIR}/backend" "${PACKAGE_DIR}/frontend" "${PACKAGE_DIR}/deploy"

cp -- "${backend_jar}" "${PACKAGE_DIR}/backend/yunlume-backend.jar"
cp -a -- "${frontend_dist}/." "${PACKAGE_DIR}/frontend/"
for template in "${REQUIRED_HOST_TEMPLATES[@]}"; do
  cp -- "${HOST_TEMPLATE_DIR}/${template}" "${PACKAGE_DIR}/deploy/${template}"
done
printf '%s\n' "${VERSION}" >"${PACKAGE_DIR}/VERSION"
find "${PACKAGE_DIR}" -type d -exec chmod 0755 {} +
find "${PACKAGE_DIR}" -type f -exec chmod 0644 {} +
(
  cd "${PACKAGE_DIR}"
  {
    find backend frontend deploy -type f -print0
    printf 'VERSION\0'
  } | sort -z | while IFS= read -r -d '' package_file; do
    sha256sum "${package_file}"
  done > SHA256SUMS
)
chmod 0644 "${PACKAGE_DIR}/SHA256SUMS"

staged_archive="${STAGING_DIR}/${ARCHIVE_NAME}"
staged_checksum="${STAGING_DIR}/${CHECKSUM_NAME}"
tar \
  --sort=name \
  --mtime='UTC 1970-01-01' \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  -C "${PACKAGE_DIR}" \
  -czf "${staged_archive}" \
  backend frontend deploy VERSION SHA256SUMS

archive_digest="$(sha256sum "${staged_archive}")"
archive_digest="${archive_digest%% *}"
[[ "${archive_digest}" =~ ^[0-9a-f]{64}$ ]] || die "无法生成有效的 SHA-256"
printf '%s  %s\n' "${archive_digest}" "${ARCHIVE_NAME}" >"${staged_checksum}"
chmod 0644 "${staged_archive}" "${staged_checksum}"

install -m 0644 "${staged_archive}" "${TEMP_ARCHIVE}"
install -m 0644 "${staged_checksum}" "${TEMP_CHECKSUM}"
trap '' INT TERM
if ! ln -- "${TEMP_ARCHIVE}" "${FINAL_ARCHIVE}"; then
  trap 'exit 130' INT
  trap 'exit 143' TERM
  die "目标发行包已被其他任务创建，拒绝覆盖"
fi
ARCHIVE_PUBLISHED="true"
rm -f -- "${TEMP_ARCHIVE}"
if ! ln -- "${TEMP_CHECKSUM}" "${FINAL_CHECKSUM}"; then
  trap 'exit 130' INT
  trap 'exit 143' TERM
  die "无法完整发布宿主机发行包与校验文件"
fi
CHECKSUM_PUBLISHED="true"
rm -f -- "${TEMP_CHECKSUM}"
PAIR_COMMITTED="true"
trap 'exit 130' INT
trap 'exit 143' TERM
printf '宿主机发行包已生成: %s\n' "${FINAL_ARCHIVE}"
printf 'SHA-256 文件已生成: %s\n' "${FINAL_CHECKSUM}"
