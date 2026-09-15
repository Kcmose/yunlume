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
query_reply() {
  local kind="$1" service="$2" id="$3" phase=target count=0 counter
  [[ "$(<"${DOCKER_UP_COUNT}")" -lt 2 ]] || phase=restore
  if [[ -f "${CURL_COUNTS}/$phase" && "$(<"${CURL_COUNTS}/$phase")" -ge 3 ]]; then phase="$phase-final"; fi
  counter="${QUERY_COUNTS}/${phase}-${kind}-${service}"
  [[ ! -f "$counter" ]] || count="$(<"$counter")"
  count=$((count + 1))
  printf '%s\n' "$count" >"$counter"
  printf '%s %s %s %s\n' "$phase" "$kind" "$service" "$count" >>"${QUERY_EVENTS}"
  if [[ "$phase" == "${QUERY_STAGE}" && "$kind" == "${QUERY_KIND}" && "$service" == "${QUERY_SERVICE}" ]] &&
     [[ "${QUERY_LIMIT}" == always || "$count" -le "${QUERY_LIMIT}" ]]; then
    case "${QUERY_FAULT}" in
      fail-valid) printf '%s\n' "$id"; exit 7 ;;
      fail-healthy) printf 'healthy\n'; exit 7 ;;
      fail-empty) exit 7 ;;
      empty) exit 0 ;;
      multiple-ids) printf '%s\n%s\n' "${BACKEND_ID}" "${FRONTEND_ID}"; exit 0 ;;
      invalid-id) printf 'not-a-container-id\n'; exit 0 ;;
      same-id) printf '%s\n' "${BACKEND_ID}"; exit 0 ;;
      multiple-health) printf 'healthy\nhealthy\n'; exit 0 ;;
      starting|unhealthy|null|unknown) printf '%s\n' "${QUERY_FAULT}"; exit 0 ;;
      *) exit 96 ;;
    esac
  fi
  if [[ "$kind" == ps ]]; then
    printf '%s\n' "$id"
  elif [[ "$phase" == target && "$service" == backend && "${FORCE_TARGET_FAILURE}" == true ]]; then
    printf 'unhealthy\n'
  else
    printf 'healthy\n'
  fi
  exit 0
}
if [[ "$1" == "inspect" ]]; then
  case "${*: -1}" in
    "${BACKEND_ID}")
      if [[ "${PROBE_SCENARIO}" == query-* ]]; then query_reply inspect backend "${BACKEND_ID}"; fi
      if [[ "$(<"${DOCKER_UP_COUNT}")" -ge 2 || "${PROBE_SCENARIO}" == target-healthz-failure ]]; then
        printf 'healthy\n'
      else
        printf 'unhealthy\n'
      fi
      ;;
    "${FRONTEND_ID}")
      if [[ "${PROBE_SCENARIO}" == query-* ]]; then query_reply inspect frontend "${FRONTEND_ID}"; fi
      printf 'healthy\n'
      ;;
    *) exit 1 ;;
  esac
  exit 0
fi
if [[ "$1" == "compose" ]]; then
  case "$*" in
    *" ps -q backend")
      if [[ "${PROBE_SCENARIO}" == query-* ]]; then query_reply ps backend "${BACKEND_ID}"; fi
      printf '%s\n' "${BACKEND_ID}"
      ;;
    *" ps -q frontend")
      if [[ "${PROBE_SCENARIO}" == query-* ]]; then query_reply ps frontend "${FRONTEND_ID}"; fi
      printf '%s\n' "${FRONTEND_ID}"
      ;;
    *" up -d --no-build --force-recreate backend frontend")
      count="$(<"${DOCKER_UP_COUNT}")"
      printf '%s\n' "$((count + 1))" >"${DOCKER_UP_COUNT}"
      if [[ "$count" -eq 0 ]]; then
        case "${PROBE_SCENARIO}" in
          query-target-term*) kill -s TERM "$PPID" ;;
          query-target-int*) kill -s INT "$PPID" ;;
        esac
      elif [[ "$count" -eq 1 ]]; then
        case "${PROBE_SCENARIO}" in
          query-target-term-then-int) kill -s INT "$PPID" ;;
          query-target-int-then-term) kill -s TERM "$PPID" ;;
        esac
      fi
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

export REAL_RM="$(command -v rm)"
cat >"${TEMP_DIR}/bin/rm" <<'EOF'
#!/usr/bin/env bash
set -eu
if [[ "${FAIL_BACKUP_REMOVAL:-false}" == true ]]; then
  for argument in "$@"; do
    if [[ "$argument" == "${ROLLBACK_TEST_PROJECT}"/.env.release-rollback-* ]]; then
      printf 'injected backup removal failure\n' >&2
      exit 7
    fi
  done
fi
exec "${REAL_RM}" "$@"
EOF
chmod +x "${TEMP_DIR}/bin/rm"
export ROLLBACK_TEST_PROJECT="${TEST_PROJECT}" FAIL_BACKUP_REMOVAL=false

cat >"${TEMP_DIR}/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -eu
[[ " $* " == *' --connect-timeout '* && " $* " == *' --max-time '* ]] || {
  printf 'curl probe is missing bounded connection/response timeouts: %s\n' "$*" >&2
  exit 98
}
url="${*: -1}"
printf '%s\n' "${url}" >>"${CURL_CALLS}"
if [[ "${PROBE_SCENARIO}" == query-* ]]; then
  phase=target
  [[ "$(<"${DOCKER_UP_COUNT}")" -lt 2 ]] || phase=restore
  count=0
  [[ ! -f "${CURL_COUNTS}/$phase" ]] || count="$(<"${CURL_COUNTS}/$phase")"
  printf '%s\n' "$((count + 1))" >"${CURL_COUNTS}/$phase"
fi
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
export BACKEND_ID=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
export FRONTEND_ID=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
export QUERY_EVENTS="${TEMP_DIR}/query.events"
export QUERY_STAGE=target QUERY_KIND=ps QUERY_SERVICE=backend QUERY_LIMIT=always QUERY_FAULT=fail-valid
export FORCE_TARGET_FAILURE=false
BACKEND_TARGET="ghcr.io/example/yunlume-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
FRONTEND_TARGET="ghcr.io/example/yunlume-frontend@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
printf 'BACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\nAPP_BIND_ADDRESS=127.0.0.1\nAPP_PORT=18080\n' \
  "${BACKEND_TARGET}" "${FRONTEND_TARGET}" >"${TEMP_DIR}/target.env"
checks=0

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
  local expected_release="${5:-original}" expected_starts="${6:-2}" keep_backup="${7:-no}"
  local status=0 recreate_count probe_count
  local output="${TEMP_DIR}/${scenario}.log"
  local -a backups=()
  export PROBE_SCENARIO="${scenario}"
  export QUERY_COUNTS="${TEMP_DIR}/counts-${scenario}"
  export CURL_COUNTS="${TEMP_DIR}/curl-counts-${scenario}"
  mkdir -p "${QUERY_COUNTS}" "${CURL_COUNTS}"
  : >"${QUERY_EVENTS}"
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
  local expected_env="${TEMP_DIR}/expected.env"
  [[ "${expected_release}" != target ]] || expected_env="${TEMP_DIR}/target.env"
  cmp --silent "${expected_env}" "${TEST_PROJECT}/.env" || {
    printf '%s: environment does not match expected %s release\n' "${scenario}" "${expected_release}" >&2
    exit 1
  }
  recreate_count="$(grep -c 'up -d --no-build --force-recreate backend frontend' "${DOCKER_CALLS}")"
  [[ "${recreate_count}" -eq "${expected_starts}" ]] || {
    printf '%s: expected %s release starts, got %s\n' "${scenario}" "${expected_starts}" "${recreate_count}" >&2
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
  if [[ "${backup_expected}" != no ]]; then
    [[ "${#backups[@]}" -eq 1 ]] || {
      printf '%s: failed recovery did not retain exactly one backup\n' "${scenario}" >&2
      exit 1
    }
    cmp --silent "${TEMP_DIR}/expected.env" "${backups[0]}" || {
      printf '%s: retained backup no longer matches the original environment\n' "${scenario}" >&2
      exit 1
    }
    if [[ "${backup_expected}" == yes ]] && ! grep -Fq '原镜像恢复未通过健康检查；备份保留在' "${output}"; then
      printf '%s: uncertain recovery was not reported\n' "${scenario}" >&2
      exit 1
    fi
    if grep -Eq '原镜像已恢复并通过健康检查|代码回滚完成' "${output}"; then
      printf '%s: failed recovery was falsely reported healthy\n' "${scenario}" >&2
      exit 1
    fi
    # 只清理当前用例实际验证过的临时备份，避免下个用例混入旧结果。
    if [[ "${keep_backup}" == yes ]]; then
      RETAINED_BACKUP="${backups[0]}"
    else
      rm -- "${backups[0]}"
    fi
  else
    [[ "${#backups[@]}" -eq 0 ]] || {
      printf '%s: backup remained after verified healthy recovery\n' "${scenario}" >&2
      exit 1
    }
    if [[ "${expected_release}" == target ]]; then
      grep -Fq '代码回滚完成' "${output}" || { cat "${output}" >&2; exit 1; }
      ! grep -Fq '正在恢复原镜像' "${output}" || exit 1
    else
      grep -Fq '原镜像已恢复并通过健康检查' "${output}" || {
        printf '%s: successful recovery was not reported\n' "${scenario}" >&2
        cat "${output}" >&2
        exit 1
      }
      ! grep -Fq '代码回滚完成' "${output}" || exit 1
    fi
  fi
  checks=$((checks + 1))
}

run_case restore-success 1 no 3
run_case target-healthz-failure 22 no 4
run_case restore-healthz-failure 2 yes 1
run_case restore-api-health-transport-failure 2 yes 2
run_case restore-install-transport-failure 2 yes 3
run_case restore-api-health-down 2 yes 3
run_case restore-api-health-invalid-json 2 yes 3
run_case restore-install-incomplete 2 yes 3

assert_fault_calls() {
  local expected="$1" counter="${QUERY_COUNTS}/${QUERY_STAGE}-${QUERY_KIND}-${QUERY_SERVICE}"
  [[ -f "$counter" && "$(<"$counter")" -eq "$expected" ]] || {
    printf '%s: query did not follow expected retry/final-check sequence (%s calls)\n' "${PROBE_SCENARIO}" "$expected" >&2
    cat "${QUERY_EVENTS}" >&2
    exit 1
  }
}

run_query_fault() {
  export QUERY_STAGE="$1" QUERY_KIND="$2" QUERY_SERVICE="$3" QUERY_FAULT="$4" QUERY_LIMIT=always
  local status=1 backup=no probes=3 calls=45
  export FORCE_TARGET_FAILURE=false
  case "$QUERY_STAGE" in
    target-final) probes=6; calls=1 ;;
    restore) status=2; backup=yes; probes=0; FORCE_TARGET_FAILURE=true ;;
    restore-final) status=2; backup=yes; probes=3; calls=1; FORCE_TARGET_FAILURE=true ;;
  esac
  run_case "query-${QUERY_STAGE}-${QUERY_KIND}-${QUERY_SERVICE}-${QUERY_FAULT}" "$status" "$backup" "$probes"
  assert_fault_calls "$calls"
}

# 查询退出状态和 stdout 独立变化，目标/恢复各自覆盖等待阶段及 HTTP 后最终复核。
for stage in target target-final restore restore-final; do
  for service in backend frontend; do
    for fault in fail-valid fail-empty empty multiple-ids; do
      run_query_fault "$stage" ps "$service" "$fault"
    done
    for fault in fail-healthy fail-empty; do
      run_query_fault "$stage" inspect "$service" "$fault"
    done
  done
  run_query_fault "$stage" ps backend invalid-id
  run_query_fault "$stage" ps frontend same-id
done

# 状态本身未知或不健康也不能成功；与“stdout healthy 但命令失败”分别验证。
for stage in target restore; do
  for fault in empty multiple-health starting unhealthy null unknown; do
    run_query_fault "$stage" inspect backend "$fault"
  done
done

# 临时查询失败后必须重新查询，不能复用失败命令留下的 ID 或 healthy 文本。
for stage in target restore; do
  for service in backend frontend; do
    for spec in ps:fail-valid ps:fail-empty ps:empty inspect:fail-healthy inspect:fail-empty inspect:starting; do
      export QUERY_STAGE="$stage" QUERY_SERVICE="$service" QUERY_KIND="${spec%%:*}" QUERY_FAULT="${spec#*:}" QUERY_LIMIT=2
      export FORCE_TARGET_FAILURE=false
      if [[ "$stage" == target ]]; then
        run_case "query-retry-${stage}-${service}-${QUERY_KIND}-${QUERY_FAULT}" 0 no 3 target 1
      else
        FORCE_TARGET_FAILURE=true
        run_case "query-retry-${stage}-${service}-${QUERY_KIND}-${QUERY_FAULT}" 1 no 3
      fi
      assert_fault_calls 3
    done
  done
done

export QUERY_STAGE=none QUERY_KIND=ps QUERY_SERVICE=backend QUERY_FAULT=fail-empty QUERY_LIMIT=always FORCE_TARGET_FAILURE=false
run_case query-healthy-target 0 no 3 target 1

# 信号触发原镜像恢复也必须保留 130/143，不能把 EXIT 上下文的 0 当作回滚成功。
run_case query-target-term 143 no 3
run_case query-target-int 130 no 3
run_case query-target-term-then-int 130 signal 0
run_case query-target-int-then-term 143 signal 0
export QUERY_STAGE=restore QUERY_KIND=inspect QUERY_SERVICE=frontend QUERY_FAULT=fail-healthy
run_case query-target-term-restore-unknown 2 yes 0

# 恢复未确认时保留的材料不能被下次成功执行顺手删除。
export QUERY_STAGE=restore-final QUERY_KIND=inspect QUERY_SERVICE=frontend QUERY_FAULT=fail-healthy FORCE_TARGET_FAILURE=true
run_case query-retain-then-retry 2 yes 3 original 2 yes
export QUERY_STAGE=none FORCE_TARGET_FAILURE=false PROBE_SCENARIO=query-after-retained-failure
export QUERY_COUNTS="${TEMP_DIR}/counts-after-retained" CURL_COUNTS="${TEMP_DIR}/curl-counts-after-retained"
mkdir -p "${QUERY_COUNTS}" "${CURL_COUNTS}"
: >"${QUERY_EVENTS}"
: >"${DOCKER_CALLS}"
: >"${CURL_CALLS}"
printf '0\n' >"${DOCKER_UP_COUNT}"
run_rollback "${BACKEND_TARGET}" "${FRONTEND_TARGET}" >"${TEMP_DIR}/after-retained.log" 2>&1 || {
  cat "${TEMP_DIR}/after-retained.log" >&2
  exit 1
}
cmp --silent "${TEMP_DIR}/target.env" "${TEST_PROJECT}/.env"
cmp --silent "${TEMP_DIR}/expected.env" "${RETAINED_BACKUP}"
[[ "$(<"${DOCKER_UP_COUNT}")" -eq 1 && "$(wc -l <"${CURL_CALLS}")" -eq 3 ]]
grep -Fq '代码回滚完成' "${TEMP_DIR}/after-retained.log"
shopt -s nullglob
remaining_backups=("${TEST_PROJECT}"/.env.release-rollback-*)
shopt -u nullglob
[[ "${#remaining_backups[@]}" -eq 1 && "${remaining_backups[0]}" == "${RETAINED_BACKUP}" ]]
rm -- "${RETAINED_BACKUP}"
checks=$((checks + 1))

# 验证已经健康但删除恢复材料失败的两条分支，不能先打印成功再丢失错误。
for release in target original; do
  export PROBE_SCENARIO="query-backup-removal-${release}" QUERY_STAGE=none FAIL_BACKUP_REMOVAL=true
  export QUERY_COUNTS="${TEMP_DIR}/counts-rm-${release}" CURL_COUNTS="${TEMP_DIR}/curl-counts-rm-${release}"
  export FORCE_TARGET_FAILURE=false
  expected_status=7
  expected_starts=1
  expected_env="${TEMP_DIR}/target.env"
  if [[ "$release" == original ]]; then
    FORCE_TARGET_FAILURE=true
    expected_status=2
    expected_starts=2
    expected_env="${TEMP_DIR}/expected.env"
  fi
  mkdir -p "${QUERY_COUNTS}" "${CURL_COUNTS}"
  cp -- "${TEMP_DIR}/expected.env" "${TEST_PROJECT}/.env"
  : >"${QUERY_EVENTS}"
  : >"${DOCKER_CALLS}"
  : >"${CURL_CALLS}"
  printf '0\n' >"${DOCKER_UP_COUNT}"
  status=0
  run_rollback "${BACKEND_TARGET}" "${FRONTEND_TARGET}" >"${TEMP_DIR}/rm-${release}.log" 2>&1 || status=$?
  [[ "$status" -eq "$expected_status" ]] || { cat "${TEMP_DIR}/rm-${release}.log" >&2; exit 1; }
  cmp --silent "$expected_env" "${TEST_PROJECT}/.env"
  [[ "$(<"${DOCKER_UP_COUNT}")" -eq "$expected_starts" && "$(wc -l <"${CURL_CALLS}")" -eq 3 ]]
  ! grep -Eq '原镜像已恢复并通过健康检查|代码回滚完成' "${TEMP_DIR}/rm-${release}.log"
  shopt -s nullglob
  removal_backups=("${TEST_PROJECT}"/.env.release-rollback-*)
  shopt -u nullglob
  [[ "${#removal_backups[@]}" -eq 1 ]]
  cmp --silent "${TEMP_DIR}/expected.env" "${removal_backups[0]}"
  FAIL_BACKUP_REMOVAL=false
  rm -- "${removal_backups[0]}"
  checks=$((checks + 1))
done

printf 'Rollback release: %s real-entry query, endpoint, retry, signal and backup recovery scenarios passed.\n' "$checks"
