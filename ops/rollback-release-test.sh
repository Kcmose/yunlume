#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEMP_DIR="$(mktemp -d -t yunlume-rollback-release-test.XXXXXXXX)"

cleanup() {
  local status=$?
  trap - EXIT
  rm -rf -- "${TEMP_DIR}"
  exit "${status}"
}
trap cleanup EXIT

# 执行真实入口和 common 函数，但让 .env/恢复备份全部落在独立项目内。
# Docker/curl/sleep 是外部边界替身；操作锁仍由隔离测试容器持有。
TEST_PROJECT="${TEMP_DIR}/project"
mkdir -p "${TEMP_DIR}/bin" "${TEST_PROJECT}/ops/lib"
cp -- "${ROOT_DIR}/ops/rollback-release.sh" "${TEST_PROJECT}/ops/rollback-release.sh"
cp -- "${ROOT_DIR}/ops/lib/common.sh" "${TEST_PROJECT}/ops/lib/common.sh"
cp -- "${ROOT_DIR}/docker-compose.yml" "${TEST_PROJECT}/docker-compose.yml"
cat >"${TEMP_DIR}/expected.env" <<'EOF'
BACKEND_IMAGE=example.invalid/yunlume-backend:old
FRONTEND_IMAGE=example.invalid/yunlume-frontend:old
APP_BIND_ADDRESS=127.0.0.1
APP_PORT=18080
EOF
chmod 0600 "${TEMP_DIR}/expected.env"

cat >"${TEMP_DIR}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >>"${DOCKER_CALLS}"
if [[ "$1" == "image" && "$2" == "inspect" ]]; then exit 0; fi
if [[ "$1" == "inspect" ]]; then
  case "${*: -1}" in
    backend-id)
      if [[ "$(<"${DOCKER_UP_COUNT}")" -ge 2 || "${PROBE_SCENARIO}" == target-healthz-failure ]]; then
        printf 'healthy\n'
      else
        printf 'unhealthy\n'
      fi
      ;;
    frontend-id) printf 'healthy\n' ;;
    *) exit 1 ;;
  esac
  exit 0
fi
if [[ "$1" == "compose" ]]; then
  case "$*" in
    *" ps -q backend") printf 'backend-id\n' ;;
    *" ps -q frontend") printf 'frontend-id\n' ;;
    *" up -d --no-build --force-recreate backend frontend")
      count="$(<"${DOCKER_UP_COUNT}")"
      printf '%s\n' "$((count + 1))" >"${DOCKER_UP_COUNT}"
      ;;
    *) exit 97 ;;
  esac
  exit 0
fi
exit 1
EOF
chmod +x "${TEMP_DIR}/bin/docker"

cat >"${TEMP_DIR}/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${TEMP_DIR}/bin/sleep"

cat >"${TEMP_DIR}/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -eu
[[ " $* " == *' --connect-timeout '* && " $* " == *' --max-time '* ]] || {
  printf 'curl probe is missing bounded connection/response timeouts: %s\n' "$*" >&2
  exit 98
}
url="${*: -1}"
printf '%s\n' "${url}" >>"${CURL_CALLS}"
case "${url}" in
  */healthz)
    if [[ "${PROBE_SCENARIO}" == restore-healthz-failure ||
          ( "${PROBE_SCENARIO}" == target-healthz-failure && "$(<"${DOCKER_UP_COUNT}")" -eq 1 ) ]]; then
      exit 22
    fi
    ;;
  */api/health)
    case "${PROBE_SCENARIO}" in
      restore-api-health-down) printf '{"data":{"status":"DOWN"}}\n' ;;
      restore-api-health-invalid-json) printf 'not-json\n' ;;
      *) printf '{"data":{"status":"UP"}}\n' ;;
    esac
    # 模拟接收到有效响应后传输仍失败：不能只凭 JSON 正确就吞掉退出码。
    [[ "${PROBE_SCENARIO}" != restore-api-health-transport-failure ]] || exit 22
    ;;
  */api/install/status)
    if [[ "${PROBE_SCENARIO}" == restore-install-incomplete ]]; then
      printf '{"data":{"state":"REQUIRED"}}\n'
    else
      printf '{"data":{"state":"COMPLETED"}}\n'
    fi
    [[ "${PROBE_SCENARIO}" != restore-install-transport-failure ]] || exit 22
    ;;
  *) exit 99 ;;
esac
EOF
chmod +x "${TEMP_DIR}/bin/curl"

export DOCKER_CALLS="${TEMP_DIR}/docker.calls"
export DOCKER_UP_COUNT="${TEMP_DIR}/docker.up-count"
export CURL_CALLS="${TEMP_DIR}/curl.calls"
BACKEND_TARGET="ghcr.io/example/yunlume-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
FRONTEND_TARGET="ghcr.io/example/yunlume-frontend@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

run_rollback() {
  PATH="${TEMP_DIR}/bin:${PATH}" \
  ENV_FILE="${TEST_PROJECT}/.env" \
  CONFIRM_ROLLBACK=ROLLBACK-RELEASE \
  CONFIRM_EXTERNAL_DATABASE_BACKUP=EXTERNAL-DATABASE-BACKUP-VERIFIED \
    bash "${TEST_PROJECT}/ops/rollback-release.sh" "$@"
}

cp -- "${TEMP_DIR}/expected.env" "${TEST_PROJECT}/.env"
export PROBE_SCENARIO="restore-success"
: >"${DOCKER_CALLS}"
: >"${CURL_CALLS}"
printf '0\n' >"${DOCKER_UP_COUNT}"
if run_rollback ghcr.io/example/yunlume-backend:latest ghcr.io/example/yunlume-frontend:1.2.3 \
    >"${TEMP_DIR}/mutable-output.log" 2>&1; then
  printf 'rollback accepted mutable image references\n' >&2
  exit 1
fi
[[ ! -s "${DOCKER_CALLS}" ]] || {
  printf 'mutable references reached Docker before rejection\n' >&2
  exit 1
}

run_case() {
  local scenario="$1" expected_status="$2" backup_expected="$3" expected_probes="$4"
  local status=0 recreate_count probe_count
  local output="${TEMP_DIR}/${scenario}.log"
  local -a backups=()
  export PROBE_SCENARIO="${scenario}"
  cp -- "${TEMP_DIR}/expected.env" "${TEST_PROJECT}/.env"
  : >"${DOCKER_CALLS}"
  : >"${CURL_CALLS}"
  printf '0\n' >"${DOCKER_UP_COUNT}"

  run_rollback "${BACKEND_TARGET}" "${FRONTEND_TARGET}" >"${output}" 2>&1 || status=$?
  [[ "${status}" -eq "${expected_status}" ]] || {
    printf '%s: expected exit %s, got %s\n' "${scenario}" "${expected_status}" "${status}" >&2
    cat "${output}" >&2
    exit 1
  }
  cmp --silent "${TEMP_DIR}/expected.env" "${TEST_PROJECT}/.env" || {
    printf '%s: original environment was not restored\n' "${scenario}" >&2
    exit 1
  }
  recreate_count="$(grep -c 'up -d --no-build --force-recreate backend frontend' "${DOCKER_CALLS}")"
  [[ "${recreate_count}" -eq 2 ]] || {
    printf '%s: expected exactly target start and previous-release recovery\n' "${scenario}" >&2
    cat "${DOCKER_CALLS}" >&2
    exit 1
  }
  probe_count="$(wc -l <"${CURL_CALLS}")"
  [[ "${probe_count}" -eq "${expected_probes}" ]] || {
    printf '%s: expected %s probes, got %s; a failed probe may have been ignored\n' \
      "${scenario}" "${expected_probes}" "${probe_count}" >&2
    cat "${CURL_CALLS}" >&2
    exit 1
  }
  shopt -s nullglob
  backups=("${TEST_PROJECT}"/.env.release-rollback-*)
  shopt -u nullglob
  if [[ "${backup_expected}" == yes ]]; then
    [[ "${#backups[@]}" -eq 1 ]] || {
      printf '%s: failed recovery did not retain exactly one backup\n' "${scenario}" >&2
      exit 1
    }
    cmp --silent "${TEMP_DIR}/expected.env" "${backups[0]}" || {
      printf '%s: retained backup no longer matches the original environment\n' "${scenario}" >&2
      exit 1
    }
    grep -Fq '原镜像恢复未通过健康检查；备份保留在' "${output}" || {
      printf '%s: uncertain recovery was not reported\n' "${scenario}" >&2
      exit 1
    }
    if grep -Eq '原镜像已恢复并通过健康检查|代码回滚完成' "${output}"; then
      printf '%s: failed recovery was falsely reported healthy\n' "${scenario}" >&2
      exit 1
    fi
    # 只清理当前用例实际验证过的临时备份，避免下个用例混入旧结果。
    rm -- "${backups[0]}"
  else
    [[ "${#backups[@]}" -eq 0 ]] || {
      printf '%s: backup remained after verified healthy recovery\n' "${scenario}" >&2
      exit 1
    }
    grep -Fq '原镜像已恢复并通过健康检查' "${output}" || {
      printf '%s: successful recovery was not reported\n' "${scenario}" >&2
      cat "${output}" >&2
      exit 1
    }
  fi
}

run_case restore-success 1 no 3
run_case target-healthz-failure 22 no 4
run_case restore-healthz-failure 2 yes 1
run_case restore-api-health-transport-failure 2 yes 2
run_case restore-install-transport-failure 2 yes 3
run_case restore-api-health-down 2 yes 3
run_case restore-api-health-invalid-json 2 yes 3
run_case restore-install-incomplete 2 yes 3

printf 'Rollback release endpoint failure propagation and backup retention checks passed.\n'
