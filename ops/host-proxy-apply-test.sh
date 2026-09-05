#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t yunlume-host-proxy-apply-test.XXXXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT
[[ "$(id -u)" == 0 ]] || { printf 'Run this isolated permission test as root.\n' >&2; exit 1; }

# 完整运行真实 install_host 及失败恢复，只把固定系统目录映射到临时目录。
# JAR/UI 是惰性文件；账户、systemd、Nginx、网络为外部边界替身，不启动服务。
python3 - "${PROJECT_DIR}" "${TEST_ROOT}" <<'PY'
import hashlib
import json
import sys
import tarfile
from pathlib import Path

project, root = map(Path, sys.argv[1:])
for version in ("1.2.3", "1.2.4"):
    assets = root / version
    package = assets / "package"
    for relative, content in {
        "backend/yunlume-backend.jar": "fixture jar\n",
        "frontend/index.html": "fixture frontend\n",
        "database/migrations/20260904_0004_portable_import_operations.sql": "-- fixture\n",
        "VERSION": version + "\n",
    }.items():
        target = package / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    for name in ("app.env.template", "yunlume-backend.service", "yunlume.nginx.conf"):
        target = package / "deploy" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text((project / "deploy/host" / name).read_text(encoding="utf-8"), encoding="utf-8")
    (package / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(package).as_posix()}\n"
        for path in sorted(package.rglob("*")) if path.is_file()
    ), encoding="utf-8")
    with tarfile.open(assets / "host.tar.gz", "w:gz") as archive:
        for path in sorted(package.iterdir()):
            archive.add(path, arcname=path.name)
    (assets / "release-manifest.json").write_text(json.dumps({
        "version": version, "compatibilityEpoch": 1,
    }) + "\n", encoding="utf-8")
PY
mkdir "${TEST_ROOT}/bin"
printf '#!/usr/bin/env bash\nexit 0\n' >"${TEST_ROOT}/bin/java"
chmod 0755 "${TEST_ROOT}/bin/java"

cat >"${TEST_ROOT}/attempt.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
case_dir="$1" assets_root="$2" target_version="$3" label="$4"
source "${case_dir}/install-lib.sh"
MODE=host
INSTALL_DIR="${case_dir}/deployment"
WORK_DIR="$(mktemp -d "${case_dir}/work.XXXXXXXX")"
VERSION="${target_version}"
APP_PORT=18080
MANIFEST_COMPATIBILITY_EPOCH=1
# 与真实 load_manifest 一致，恢复归档要能在工作目录找到本次发行清单。
cp -- "${assets_root}/${VERSION}/release-manifest.json" "${WORK_DIR}/release-manifest.json"
MANIFEST_FILE="${WORK_DIR}/release-manifest.json"
MANIFEST_HOST_ARCHIVE=host.tar.gz
MANIFEST_HOST_ARCHIVE_SHA256="$(sha256sum "${assets_root}/${VERSION}/host.tar.gz" | awk '{print $1}')"
RELEASE_ASSET_BASE=https://example.invalid/fixture
export PATH="${assets_root}/bin:${PATH}"
calls="${case_dir}/${label}.calls"
touch "${calls}"
require_command() { :; }
download_file() { cp -- "${assets_root}/${VERSION}/${1##*/}" "$2"; }
java_major_version() { printf '17\n'; }
ensure_service_user() { printf 'service-user\n' >>"${calls}"; }
chown() { :; }
install() {
  local -a args=()
  while (( $# )); do
    case "$1" in -o|-g) shift 2 ;; *) args+=("$1"); shift ;; esac
  done
  command install "${args[@]}"
}
systemctl() {
  printf 'systemctl %s\n' "$*" >>"${calls}"
  if [[ "$1" == is-active && "$2" == yunlume-backend.service && -f "${case_dir}/inactive" ]]; then
    return 3
  fi
  if [[ "$1" == restart ]]; then
    if [[ -f "${WORK_DIR}/restarted" ]]; then
      touch "${WORK_DIR}/restored"
    else
      touch "${WORK_DIR}/restarted"
    fi
  fi
  return 0
}
nginx() { printf 'nginx %s\n' "$*" >>"${calls}"; }
journalctl() { :; }
sleep() { :; }
curl() {
  local url="${*: -1}" phase=before
  [[ ! -f "${WORK_DIR}/restarted" ]] || phase=after
  [[ ! -f "${WORK_DIR}/restored" ]] || phase=restore
  printf 'curl %s %s\n' "${phase}" "${url}" >>"${calls}"
  case "${url}" in
    */healthz)
      if [[ "${phase}" == restore && -f "${case_dir}/restore-http-failure" ]]; then return 22; fi
      return 0
      ;;
    */api/health)
      if [[ "${phase}" == restore ]]; then
        cat "${case_dir}/restore-health.json"
      else
        cat "${case_dir}/health.json"
      fi
      ;;
    */api/install/status)
      if [[ "${phase}" == before ]]; then
        [[ "${url}" == http://127.0.0.1:18081/api/install/status && "$*" == *"--noproxy *"* ]] || return 98
        [[ ! -f "${case_dir}/network-failure" ]] || return 7
      fi
      cat "${case_dir}/${phase}.json"
      ;;
    *) return 99 ;;
  esac
}
# 缩短重试次数，保留生产探测函数及所有真实状态/响应判定。
eval "$(declare -f wait_for_http | sed '1s/wait_for_http/real_wait_for_http/')"
eval "$(declare -f wait_for_host_pending_install | sed '1s/wait_for_host_pending_install/real_wait_for_host_pending_install/')"
wait_for_http() { real_wait_for_http "$1" 1 "${3:-false}"; }
wait_for_host_pending_install() { real_wait_for_host_pending_install "$1" "$2" 1; }
install_host
printf 'INSTALL_SUCCESS\n'
SH

prepare_case() {
  local case_dir="${TEST_ROOT}/$1"
  mkdir -p "${case_dir}/system/run/systemd/system" "${case_dir}/system/etc/nginx/conf.d" \
    "${case_dir}/system/etc/systemd/system"
  python3 - "${PROJECT_DIR}/install.sh" "${case_dir}" <<'PY'
import sys
from pathlib import Path

source_file, case = map(Path, sys.argv[1:])
source = source_file.read_text(encoding="utf-8")
entry = '\nmain "$@"\n'
assert source.count(entry) == 1
source = source.replace(entry, "\n")
for boundary in ("/etc/yunlume", "/etc/nginx/conf.d", "/etc/systemd/system", "/run/systemd/system", "/var/lib/yunlume"):
    assert boundary in source
    source = source.replace(boundary, str(case / "system") + boundary)
(case / "install-lib.sh").write_text(source, encoding="utf-8")
PY
  write_responses "${case_dir}" DATABASE_REQUIRED DATABASE_REQUIRED INSTALLING
  run_attempt "$1" 1.2.3 initial 0
  python3 - "${case_dir}/system/etc/yunlume/app.env" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
source = source.replace("HOST_BIND_ADDRESS=0.0.0.0", "HOST_BIND_ADDRESS=127.0.0.1")
source = source.replace("HOST_TRUST_PROXY_HEADERS=false", "HOST_TRUST_PROXY_HEADERS=true")
source = source.replace("HOST_TRUSTED_PROXY_CIDR=127.0.0.1/32", "HOST_TRUSTED_PROXY_CIDR=127.0.0.2/32")
path.write_text(source, encoding="utf-8")
PY
  cp "${case_dir}/system/etc/yunlume/nginx.conf" "${case_dir}/original.nginx.conf"
}

write_responses() {
  python3 - "$@" <<'PY'
import json
import sys
from pathlib import Path
case, before, after, health = sys.argv[1:5]
restore = sys.argv[5] if len(sys.argv) > 5 else before
restore_health = sys.argv[6] if len(sys.argv) > 6 else ("UP" if restore in {"REQUIRED", "COMPLETED"} else "INSTALLING")
case = Path(case)
for phase, state in (("before", before), ("after", after), ("restore", restore)):
    pending = state in {"DATABASE_REQUIRED", "REDIS_REQUIRED", "REQUIRED"}
    data = {"state": state, "installationRequired": pending, "webInstallEnabled": True,
            "ready": state in {"REQUIRED", "COMPLETED"}}
    (case / f"{phase}.json").write_text(json.dumps({"code": 200, "message": "success", "data": data}), encoding="utf-8")
for name, status in (("health.json", health), ("restore-health.json", restore_health)):
    (case / name).write_text(json.dumps({"code": 200, "message": "success", "data": {
        "status": status, "service": "nav-backend", "timestamp": "2026-09-05T00:00:00Z",
    }}), encoding="utf-8")
PY
}

run_attempt() {
  local name="$1" version="$2" label="$3" expected="$4" actual=0
  bash "${TEST_ROOT}/attempt.sh" "${TEST_ROOT}/${name}" "${TEST_ROOT}" "${version}" "${label}" \
    >"${TEST_ROOT}/${name}/${label}.out" 2>&1 || actual=$?
  if [[ "${actual}" != "${expected}" ]]; then
    printf '%s/%s expected exit %s; got %s\n' "${name}" "${label}" "${expected}" "${actual}" >&2
    cat "${TEST_ROOT}/${name}/${label}.out" >&2
    exit 1
  fi
}

for specification in \
  'database DATABASE_REQUIRED DATABASE_REQUIRED INSTALLING 0' \
  'redis REDIS_REQUIRED REDIS_REQUIRED INSTALLING 0' \
  'admin REQUIRED REQUIRED UP 0' \
  'progress DATABASE_REQUIRED REDIS_REQUIRED INSTALLING 0' \
  'finished REQUIRED COMPLETED UP 0' \
  'completed COMPLETED COMPLETED UP 0' \
  'completed-regression COMPLETED DATABASE_REQUIRED INSTALLING 1' \
  'pending-regression REDIS_REQUIRED DATABASE_REQUIRED INSTALLING 1' \
  'pending-unknown REQUIRED UNKNOWN INSTALLING 1' \
  'completed-unhealthy REQUIRED COMPLETED INSTALLING 1' \
  'upgrade DATABASE_REQUIRED DATABASE_REQUIRED INSTALLING 1'; do
  read -r name before after health expected <<<"${specification}"
  prepare_case "${name}"
  write_responses "${TEST_ROOT}/${name}" "${before}" "${after}" "${health}"
  version=1.2.3
  if [[ "${name}" == upgrade ]]; then version=1.2.4; fi
  run_attempt "${name}" "${version}" apply "${expected}"
  if [[ "${expected}" == 0 ]]; then
    grep -Fxq INSTALL_SUCCESS "${TEST_ROOT}/${name}/apply.out"
    grep -Fq 'set_real_ip_from 127.0.0.2/32;' "${TEST_ROOT}/${name}/system/etc/yunlume/nginx.conf"
    grep -Fxq 'HOST_TRUST_PROXY_HEADERS=true' "${TEST_ROOT}/${name}/system/etc/yunlume/app.env"
  else
    cmp --silent "${TEST_ROOT}/${name}/original.nginx.conf" "${TEST_ROOT}/${name}/system/etc/yunlume/nginx.conf"
    [[ "$(cat "${TEST_ROOT}/${name}/deployment/VERSION")" == 1.2.3 ]]
    if [[ "${name}" != upgrade ]]; then
      [[ ! -e "${TEST_ROOT}/${name}/deployment/recovery" ]]
    else
      # 跨版本未记录可信旧向导状态，恢复不能因此自动降低完成门禁。
      [[ -n "$(find "${TEST_ROOT}/${name}/deployment/recovery" -name previous.app.env -print -quit)" ]]
    fi
  fi
done

for name in world-writable foreign-owner unknown disabled bad-flags missing-field bad-code string-boolean network inactive; do
  prepare_case "${name}"
  case_dir="${TEST_ROOT}/${name}"
  env_file="${case_dir}/system/etc/yunlume/app.env"
  case "${name}" in
    world-writable) chmod 0666 "${env_file}" ;;
    foreign-owner) chown 1000:1000 "${env_file}" ;;
    unknown) write_responses "${case_dir}" UNKNOWN DATABASE_REQUIRED INSTALLING ;;
    disabled) write_responses "${case_dir}" DISABLED DATABASE_REQUIRED INSTALLING ;;
    network) touch "${case_dir}/network-failure" ;;
    inactive) touch "${case_dir}/inactive" ;;
    *)
      python3 - "${case_dir}/before.json" "${name}" <<'PY'
import json
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
case = sys.argv[2]
if case == "bad-flags": data["data"]["installationRequired"] = False
elif case == "missing-field": del data["data"]["webInstallEnabled"]
elif case == "bad-code": data["code"] = 403
elif case == "string-boolean": data["data"]["installationRequired"] = "true"
else: raise AssertionError(case)
path.write_text(json.dumps(data), encoding="utf-8")
PY
      ;;
  esac
  original_stat="$(stat -c '%u:%g:%a:%d:%i' "${env_file}")"
  original_sha="$(sha256sum "${env_file}")"
  run_attempt "${name}" 1.2.3 apply 1
  [[ "$(stat -c '%u:%g:%a:%d:%i' "${env_file}")" == "${original_stat}" ]]
  [[ "$(sha256sum "${env_file}")" == "${original_sha}" ]]
  ! grep -Fq 'systemctl restart' "${case_dir}/apply.calls"
  cmp --silent "${case_dir}/original.nginx.conf" "${case_dir}/system/etc/yunlume/nginx.conf"
  if [[ "${name}" == world-writable || "${name}" == foreign-owner ]]; then
    ! grep -Fxq service-user "${case_dir}/apply.calls"
    ! grep -Fq 'curl before' "${case_dir}/apply.calls"
  fi
done

for specification in \
  'restore-database DATABASE_REQUIRED DATABASE_REQUIRED INSTALLING clean' \
  'restore-redis REDIS_REQUIRED REDIS_REQUIRED INSTALLING clean' \
  'restore-admin REQUIRED REQUIRED UP clean' \
  'restore-regression REQUIRED DATABASE_REQUIRED INSTALLING retain' \
  'restore-http REQUIRED REQUIRED UP retain' \
  'restore-completed-regression COMPLETED REQUIRED UP retain'; do
  read -r name before restored health recovery <<<"${specification}"
  prepare_case "${name}"
  case_dir="${TEST_ROOT}/${name}"
  write_responses "${case_dir}" "${before}" UNKNOWN INSTALLING "${restored}" "${health}"
  if [[ "${name}" == restore-http ]]; then touch "${case_dir}/restore-http-failure"; fi
  run_attempt "${name}" 1.2.3 apply 1
  [[ "$(grep -Fxc 'systemctl restart yunlume-backend.service' "${case_dir}/apply.calls")" == 2 ]]
  grep -Fxq 'curl restore http://127.0.0.1:18080/healthz' "${case_dir}/apply.calls"
  cmp --silent "${case_dir}/original.nginx.conf" "${case_dir}/system/etc/yunlume/nginx.conf"
  [[ "$(cat "${case_dir}/deployment/VERSION")" == 1.2.3 ]]
  if [[ "${recovery}" == clean ]]; then
    grep -Fxq 'curl restore http://127.0.0.1:18080/api/install/status' "${case_dir}/apply.calls"
    [[ ! -e "${case_dir}/deployment/recovery" ]]
    ! grep -Fq '回滚未能完整恢复' "${case_dir}/apply.out"
  else
    [[ -n "$(find "${case_dir}/deployment/recovery" -name previous.app.env -print -quit)" ]]
    grep -Fq '回滚未能完整恢复' "${case_dir}/apply.out"
  fi
done

printf 'Host proxy apply: 11 state transitions, 10 pre-mutation rejections and 6 three-phase recovery cases passed through the real installer.\n'
