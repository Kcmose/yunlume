#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

# Code-only rollback for externally managed PostgreSQL and Redis deployments.
# External services, database_config and upload volumes are never changed.

# shellcheck source=ops/lib/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/lib/common.sh"

[[ $# -eq 2 ]] || die "用法: $0 <后端镜像引用> <前端镜像引用>"
[[ "${CONFIRM_ROLLBACK:-}" == "ROLLBACK-RELEASE" ]] ||
  die "请显式设置 CONFIRM_ROLLBACK=ROLLBACK-RELEASE"
[[ "${CONFIRM_EXTERNAL_DATABASE_BACKUP:-}" == "EXTERNAL-DATABASE-BACKUP-VERIFIED" ]] ||
  die "请先在数据库服务商完成并验证备份，再设置 CONFIRM_EXTERNAL_DATABASE_BACKUP=EXTERNAL-DATABASE-BACKUP-VERIFIED"
backend_target="$1"
frontend_target="$2"

assert_project_directory
require_command docker
require_command python3
require_command curl
acquire_operations_lock
load_environment
docker image inspect "${backend_target}" >/dev/null 2>&1 || die "后端镜像不存在: ${backend_target}"
docker image inspect "${frontend_target}" >/dev/null 2>&1 || die "前端镜像不存在: ${frontend_target}"

old_backend="${BACKEND_IMAGE:-yunlume-backend:latest}"
old_frontend="${FRONTEND_IMAGE:-yunlume-frontend:latest}"
rollback_env="${PROJECT_DIR}/.env.release-rollback-$(date -u +'%Y%m%d%H%M%S')-$$"
cp --preserve=mode,timestamps -- "${ENV_FILE}" "${rollback_env}"
chmod 0600 "${rollback_env}"

update_image_refs() {
  ENV_UPDATE_FILE="${ENV_FILE}" ENV_BACKEND_IMAGE="$1" ENV_FRONTEND_IMAGE="$2" python3 <<'PY'
import os
from pathlib import Path

path = Path(os.environ["ENV_UPDATE_FILE"])
updates = {"BACKEND_IMAGE": os.environ["ENV_BACKEND_IMAGE"], "FRONTEND_IMAGE": os.environ["ENV_FRONTEND_IMAGE"]}
lines = path.read_text(encoding="utf-8").splitlines()
seen = set()
out = []
for line in lines:
    key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else None
    if key in updates:
        out.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in updates.items():
    if key not in seen:
        out.append(f"{key}={value}")
temp = path.with_name(path.name + ".tmp")
temp.write_text("\n".join(out) + "\n", encoding="utf-8")
os.chmod(temp, 0o600)
os.replace(temp, path)
PY
}

recover_previous_release() {
  local status=$?
  info "目标镜像未通过健康检查，正在恢复原镜像..."
  cp --preserve=mode,timestamps -- "${rollback_env}" "${ENV_FILE}"
  chmod 0600 "${ENV_FILE}"
  export BACKEND_IMAGE="${old_backend}"
  export FRONTEND_IMAGE="${old_frontend}"
  compose up -d --no-build --force-recreate backend frontend || true
  rm -f -- "${rollback_env}"
  exit "${status}"
}
trap recover_previous_release ERR

update_image_refs "${backend_target}" "${frontend_target}"
export BACKEND_IMAGE="${backend_target}"
export FRONTEND_IMAGE="${frontend_target}"
compose up -d --no-build --force-recreate backend frontend

for _ in $(seq 1 45); do
  backend_id="$(compose ps -q backend)"
  frontend_id="$(compose ps -q frontend)"
  if [[ -n "${backend_id}" && -n "${frontend_id}" ]] &&
     [[ "$(docker inspect --format '{{.State.Health.Status}}' "${backend_id}")" == "healthy" ]] &&
     [[ "$(docker inspect --format '{{.State.Health.Status}}' "${frontend_id}")" == "healthy" ]]; then
    break
  fi
  sleep 2
done
[[ -n "${backend_id:-}" && -n "${frontend_id:-}" ]] ||
  die "回滚后的服务容器不完整"
[[ "$(docker inspect --format '{{.State.Health.Status}}' "${backend_id}")" == "healthy" ]] ||
  die "回滚后的后端未通过健康检查"
[[ "$(docker inspect --format '{{.State.Health.Status}}' "${frontend_id}")" == "healthy" ]] ||
  die "回滚后的前端未通过健康检查"

probe_host="${APP_BIND_ADDRESS:-127.0.0.1}"
case "${probe_host}" in
  0.0.0.0) probe_host="127.0.0.1" ;;
  ::|'[::]') probe_host="[::1]" ;;
  *:*) [[ "${probe_host}" == \[*\] ]] || probe_host="[${probe_host}]" ;;
esac
curl --fail --silent --show-error "http://${probe_host}:${APP_PORT:-8080}/healthz" >/dev/null
health_json="$(curl --fail --silent --show-error \
  "http://${probe_host}:${APP_PORT:-8080}/api/health")"
install_json="$(curl --fail --silent --show-error \
  "http://${probe_host}:${APP_PORT:-8080}/api/install/status")"
HEALTH_JSON="${health_json}" INSTALL_JSON="${install_json}" python3 <<'PY'
import json
import os

health = json.loads(os.environ["HEALTH_JSON"])
install = json.loads(os.environ["INSTALL_JSON"])
if health.get("data", {}).get("status") != "UP":
    raise SystemExit("回滚后的后端仅处于安装态，未达到 UP")
if install.get("data", {}).get("state") != "COMPLETED":
    raise SystemExit("回滚后的站点未保持 COMPLETED，拒绝把安装页当作成功回滚")
PY

trap - ERR
rm -f -- "${rollback_env}"
info "代码回滚完成；外部数据库、外部 Redis、database_config 与上传卷均未更换"
