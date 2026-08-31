#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
LOCK_FILE="/run/lock/yunlume-operations.lock"
EXPECTED_BUSY="已有 yunlume 操作正在运行"
TEMP_DIR="$(mktemp -d -t yunlume-operations-lock-test.XXXXXXXX)"

cleanup() {
  local status=$?
  trap - EXIT
  exec 8>&- || true
  rm -rf -- "${TEMP_DIR}"
  exit "${status}"
}
trap cleanup EXIT

assert_contains() {
  local text="$1"
  local expected="$2"
  local label="$3"
  [[ "${text}" == *"${expected}"* ]] || {
    printf '%s did not contain %q:\n%s\n' "${label}" "${expected}" "${text}" >&2
    return 1
  }
}

assert_not_contains() {
  local text="$1"
  local unexpected="$2"
  local label="$3"
  [[ "${text}" != *"${unexpected}"* ]] || {
    printf '%s unexpectedly contained %q:\n%s\n' "${label}" "${unexpected}" "${text}" >&2
    return 1
  }
}

run_expect_failure() {
  local output_file="$1"
  shift
  if "$@" >"${output_file}" 2>&1; then
    printf 'command unexpectedly succeeded: %q ' "$@" >&2
    printf '\n' >&2
    return 1
  fi
  cat "${output_file}"
}

cat >"${TEMP_DIR}/rollback.env" <<'EOF'
BACKEND_IMAGE=example.invalid/yunlume-backend:1.0.5
FRONTEND_IMAGE=example.invalid/yunlume-frontend:1.0.5
APP_BIND_ADDRESS=127.0.0.1
APP_PORT=18080
EOF
chmod 0600 "${TEMP_DIR}/rollback.env"

install_command=(
  bash "${ROOT_DIR}/install.sh"
  --mode docker
  --version 1.0.5
  --install-dir /opt/yunlume-lock-test
  --release-base-url http://127.0.0.1:9
)
rollback_command=(
  env
  ENV_FILE="${TEMP_DIR}/rollback.env"
  CONFIRM_ROLLBACK=ROLLBACK-RELEASE
  CONFIRM_EXTERNAL_DATABASE_BACKUP=EXTERNAL-DATABASE-BACKUP-VERIFIED
  bash "${ROOT_DIR}/ops/rollback-release.sh"
  example.invalid/yunlume-backend:1.0.5
  example.invalid/yunlume-frontend:1.0.5
)
migration_command=(
  bash "${ROOT_DIR}/ops/migrate-docker-volumes.sh"
  --source-uploads-volume lock-test-source-uploads
  --source-config-volume lock-test-source-config
  --destination-uploads-volume lock-test-destination-uploads
  --destination-config-volume lock-test-destination-config
  --helper-image example.invalid/yunlume-helper:missing
  --report-dir /var/lib/yunlume/lock-test-reports
  --execute
)

install -d -m 0755 "$(dirname -- "${LOCK_FILE}")"
exec 8>"${LOCK_FILE}"
flock -n 8

for entry in install rollback migration; do
  case "${entry}" in
    install) command=("${install_command[@]}") ;;
    rollback) command=("${rollback_command[@]}") ;;
    migration) command=("${migration_command[@]}") ;;
  esac
  output="$(run_expect_failure "${TEMP_DIR}/${entry}.busy.log" "${command[@]}")"
  assert_contains "${output}" "${EXPECTED_BUSY}" "${entry} lock contention"
done

flock -u 8
exec 8>&-

install_output="$(run_expect_failure "${TEMP_DIR}/install.released.log" "${install_command[@]}")"
assert_not_contains "${install_output}" "${EXPECTED_BUSY}" "install released lock"
assert_contains "${install_output}" "curl:" "install released lock"

rollback_output="$(run_expect_failure "${TEMP_DIR}/rollback.released.log" "${rollback_command[@]}")"
assert_not_contains "${rollback_output}" "${EXPECTED_BUSY}" "rollback released lock"
assert_contains "${rollback_output}" "后端镜像不存在" "rollback released lock"

migration_output="$(run_expect_failure "${TEMP_DIR}/migration.released.log" "${migration_command[@]}")"
assert_not_contains "${migration_output}" "${EXPECTED_BUSY}" "migration released lock"
assert_contains "${migration_output}" "本机不存在 helper 镜像" "migration released lock"

for source_file in \
  "${ROOT_DIR}/install.sh" \
  "${ROOT_DIR}/ops/lib/common.sh" \
  "${ROOT_DIR}/ops/migrate-docker-volumes.sh"; do
  source_text="$(<"${source_file}")"
  assert_contains "${source_text}" "/run/lock/yunlume-operations.lock" "${source_file} lock path"
  assert_not_contains "${source_text}" "install.lock" "${source_file} legacy install lock"
  assert_not_contains "${source_text}" "volume-migration.lock" "${source_file} legacy migration lock"
  assert_contains "${source_text}" "不能是符号链接" "${source_file} symlink hardening"
  assert_contains "${source_text}" "必须属于 root" "${source_file} ownership hardening"
done

common_source="$(<"${ROOT_DIR}/ops/lib/common.sh")"
assert_not_contains "${common_source}" '${OPERATIONS_LOCK:-' "rollback lock override"

printf 'Unified operations lock contention and release checks passed.\n'
