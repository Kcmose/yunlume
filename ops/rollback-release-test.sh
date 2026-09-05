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

mkdir -p "${TEMP_DIR}/bin"
cat >"${TEMP_DIR}/rollback.env" <<'EOF'
BACKEND_IMAGE=example.invalid/yunlume-backend:old
FRONTEND_IMAGE=example.invalid/yunlume-frontend:old
APP_BIND_ADDRESS=127.0.0.1
APP_PORT=18080
EOF
chmod 0600 "${TEMP_DIR}/rollback.env"
cp -- "${TEMP_DIR}/rollback.env" "${TEMP_DIR}/expected.env"

cat >"${TEMP_DIR}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >>"${DOCKER_CALLS}"
if [[ "$1" == "image" && "$2" == "inspect" ]]; then
  exit 0
fi
if [[ "$1" == "inspect" ]]; then
  case "${*: -1}" in
    backend-id)
      if [[ "$(<"${DOCKER_UP_COUNT}")" -ge 2 ]]; then printf 'healthy\n'; else printf 'unhealthy\n'; fi
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
      exit 0
      ;;
    *) exit 0 ;;
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
[[ " $* " == *' --connect-timeout '* && " $* " == *' --max-time '* ]] || {
  printf 'curl probe is missing bounded connection/response timeouts: %s\n' "$*" >&2
  exit 98
}
case "${*: -1}" in
  */healthz) exit 0 ;;
  */api/health) printf '{"data":{"status":"UP"}}\n' ;;
  */api/install/status) printf '{"data":{"state":"COMPLETED"}}\n' ;;
  *) exit 99 ;;
esac
EOF
chmod +x "${TEMP_DIR}/bin/curl"

export DOCKER_CALLS="${TEMP_DIR}/docker.calls"
export DOCKER_UP_COUNT="${TEMP_DIR}/docker.up-count"
: >"${DOCKER_CALLS}"
printf '0\n' >"${DOCKER_UP_COUNT}"

set +e
PATH="${TEMP_DIR}/bin:${PATH}" \
ENV_FILE="${TEMP_DIR}/rollback.env" \
CONFIRM_ROLLBACK=ROLLBACK-RELEASE \
CONFIRM_EXTERNAL_DATABASE_BACKUP=EXTERNAL-DATABASE-BACKUP-VERIFIED \
bash "${ROOT_DIR}/ops/rollback-release.sh" \
  ghcr.io/example/yunlume-backend:latest \
  ghcr.io/example/yunlume-frontend:1.2.3 \
  >"${TEMP_DIR}/mutable-output.log" 2>&1
mutable_status=$?
set -e
[[ "${mutable_status}" -ne 0 ]] || {
  printf 'rollback accepted mutable image references\n' >&2
  exit 1
}

set +e
PATH="${TEMP_DIR}/bin:${PATH}" \
ENV_FILE="${TEMP_DIR}/rollback.env" \
CONFIRM_ROLLBACK=ROLLBACK-RELEASE \
CONFIRM_EXTERNAL_DATABASE_BACKUP=EXTERNAL-DATABASE-BACKUP-VERIFIED \
bash "${ROOT_DIR}/ops/rollback-release.sh" \
  ghcr.io/example/yunlume-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  ghcr.io/example/yunlume-frontend@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  >"${TEMP_DIR}/output.log" 2>&1
status=$?
set -e

[[ "${status}" -ne 0 ]] || {
  printf 'rollback unexpectedly succeeded\n' >&2
  exit 1
}
cmp --silent "${TEMP_DIR}/expected.env" "${TEMP_DIR}/rollback.env" || {
  printf 'original environment was not restored after target health failure\n' >&2
  cat "${TEMP_DIR}/output.log" >&2
  exit 1
}
recreate_count="$(grep -c 'up -d --no-build --force-recreate backend frontend' "${DOCKER_CALLS}")"
[[ "${recreate_count}" -eq 2 ]] || {
  printf 'expected target start and previous-release recovery, got %s recreates\n' "${recreate_count}" >&2
  cat "${DOCKER_CALLS}" >&2
  exit 1
}
if compgen -G "${TEMP_DIR}/.env.release-rollback-*" >/dev/null; then
  printf 'rollback backup was not cleaned after successful recovery\n' >&2
  exit 1
fi

grep -Fq '原镜像已恢复并通过健康检查' "${TEMP_DIR}/output.log" || {
  printf 'successful recovery was not reported\n' >&2
  cat "${TEMP_DIR}/output.log" >&2
  exit 1
}

printf 'Rollback release health-failure recovery check passed.\n'
