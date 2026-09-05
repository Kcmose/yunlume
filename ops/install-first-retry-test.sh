#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_WORK_DIR="$(mktemp -d -t yunlume-install-first-retry-test.XXXXXXXX)"
trap 'rm -rf -- "${TEST_WORK_DIR}"' EXIT
[[ "$(id -u)" == 0 ]] || { printf 'Run this isolated installer test as root.\n' >&2; exit 1; }

# 保留完整安装/回滚/EXIT 函数；仅把宿主机固定系统路径映射到测试目录。
# 不调用 main，不获取真实操作锁；Docker、systemd、账户和健康探测是外部边界替身。
python3 - "${PROJECT_DIR}" "${TEST_WORK_DIR}" <<'PY'
import hashlib
import sys
import tarfile
from pathlib import Path

project, work = map(Path, sys.argv[1:])
package = work / "package"
for relative, content in {
    "backend/yunlume-backend.jar": "fixture jar\n",
    "frontend/index.html": "fixture frontend\n",
    "database/migrations/20260904_0004_portable_import_operations.sql": "-- fixture\n",
    "VERSION": "1.2.3\n",
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
with tarfile.open(work / "host.tar.gz", "w:gz") as archive:
    for path in sorted(package.iterdir()):
        archive.add(path, arcname=path.name)
(work / "compose.yml").write_text("services: {}\n", encoding="utf-8")
(work / "release-manifest.json").write_text('{"version":"1.2.3"}\n', encoding="utf-8")
PY

mkdir -p "${TEST_WORK_DIR}/bin"
printf '#!/usr/bin/env bash\nexit 0\n' >"${TEST_WORK_DIR}/bin/java"
chmod 0755 "${TEST_WORK_DIR}/bin/java"

cat >"${TEST_WORK_DIR}/attempt.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
TEST_CASE_DIR="$1"
TEST_ASSETS="$2"
TEST_MODE="$3"
SCENARIO="$4"
# shellcheck source=/dev/null
source "${TEST_CASE_DIR}/install-lib.sh"
MODE="${TEST_MODE}"
INSTALL_DIR="${TEST_CASE_DIR}/deployment"
WORK_DIR="$(mktemp -d "${TEST_CASE_DIR}/work.XXXXXXXX")"
VERSION=1.2.3
MANIFEST_COMPATIBILITY_EPOCH=1
MANIFEST_FILE="${TEST_ASSETS}/release-manifest.json"
MANIFEST_COMPOSE=compose.yml
MANIFEST_COMPOSE_SHA256="$(sha256sum "${TEST_ASSETS}/compose.yml" | awk '{print $1}')"
MANIFEST_BACKEND_IMAGE=example.invalid/backend@sha256:fixture
MANIFEST_FRONTEND_IMAGE=example.invalid/frontend@sha256:fixture
MANIFEST_HOST_ARCHIVE=host.tar.gz
MANIFEST_HOST_ARCHIVE_SHA256="$(sha256sum "${TEST_ASSETS}/host.tar.gz" | awk '{print $1}')"
RELEASE_ASSET_BASE=https://example.invalid/fixture
export PATH="${TEST_ASSETS}/bin:${PATH}"

require_command() { :; }
download_file() { cp -- "${TEST_ASSETS}/${1##*/}" "$2"; }
java_major_version() { printf '17\n'; }
ensure_service_user() {
  if [[ "${SCENARIO}" == host-prepare ]]; then return 41; fi
}
chown() { :; }
install() {
  local -a args=()
  while (( $# )); do
    case "$1" in
      -o|-g) shift 2 ;;
      *) args+=("$1"); shift ;;
    esac
  done
  command install "${args[@]}"
}
cp() {
  if [[ "${SCENARIO}" == host-partial-release && "$*" == *releases/*.tmp.* ]]; then return 42; fi
  command cp "$@"
}
rm() {
  if [[ "${SCENARIO}" == cleanup-failure && "$*" == *"${TEST_CASE_DIR}/work."* ]]; then return 48; fi
  command rm "$@"
}
systemctl() {
  printf 'systemctl %s\n' "$*" >>"${TEST_CASE_DIR}/calls"
  case "$1" in is-enabled|is-active) return 1 ;; esac
}
journalctl() { :; }
nginx() {
  printf 'nginx %s\n' "$*" >>"${TEST_CASE_DIR}/calls"
  if [[ "${SCENARIO}" == host-config ]]; then return 43; fi
}
docker() {
  printf 'docker %s\n' "$*" >>"${TEST_CASE_DIR}/calls"
  case " $* " in
    *' pull '*)
      case "${SCENARIO}" in
        docker-pull|cleanup-failure) return 45 ;;
        sigint) kill -s INT "$$" ;;
        sigterm) kill -s TERM "$$" ;;
        sigkill) kill -s KILL "$$" ;;
      esac
      ;;
    *' down '*)
      case "${SCENARIO}" in docker-rollback|term-rollback-failure) return 47 ;; esac
      ;;
  esac
  return 0
}
wait_for_http() {
  printf 'health %s\n' "$*" >>"${TEST_CASE_DIR}/calls"
  case "${SCENARIO}" in
    docker-health|host-health|docker-rollback) return 44 ;;
    term-rollback-failure) kill -s TERM "$$" ;;
  esac
  return 0
}

if [[ "${MODE}" == docker ]]; then
  install_docker
else
  install_host
fi
printf 'INSTALL_SUCCESS\n'
SH

prepare_case() {
  local case_dir="${TEST_WORK_DIR}/$1"
  mkdir -p "${case_dir}/system/run/systemd/system" \
    "${case_dir}/system/etc/nginx/conf.d" "${case_dir}/system/etc/systemd/system"
  python3 - "${PROJECT_DIR}/install.sh" "${case_dir}" <<'PY'
import sys
from pathlib import Path

source_file, case = map(Path, sys.argv[1:])
source = source_file.read_text(encoding="utf-8")
entrypoint = '\nmain "$@"\n'
if source.count(entrypoint) != 1:
    raise SystemExit("install.sh entrypoint is not uniquely identifiable")
source = source.replace(entrypoint, "\n")
for system_path in ("/etc/yunlume", "/etc/nginx/conf.d", "/etc/systemd/system", "/run/systemd/system", "/var/lib/yunlume"):
    if system_path not in source:
        raise SystemExit(f"Missing expected installer system boundary: {system_path}")
    source = source.replace(system_path, str(case / "system") + system_path)
(case / "install-lib.sh").write_text(source, encoding="utf-8")
PY
}

run_attempt() {
  local name="$1" mode="$2" scenario="$3" expected="$4" label="$5"
  local actual=0
  bash "${TEST_WORK_DIR}/attempt.sh" "${TEST_WORK_DIR}/${name}" "${TEST_WORK_DIR}" \
    "${mode}" "${scenario}" >"${TEST_WORK_DIR}/${name}/${label}.out" 2>&1 || actual=$?
  if [[ "${actual}" -ne "${expected}" ]]; then
    printf '%s/%s: expected exit %s, got %s\n' "${name}" "${label}" "${expected}" "${actual}" >&2
    cat "${TEST_WORK_DIR}/${name}/${label}.out" >&2
    exit 1
  fi
}

assert_retry_record() {
  local deployment="${TEST_WORK_DIR}/$1/deployment" mode="$2"
  [[ -f "${deployment}/.install-mode" && -f "${deployment}/.install-retry" ]]
  [[ "$(stat -c '%u:%a' "${deployment}/.install-retry")" == 0:600 ]]
  [[ "$(cat "${deployment}/.install-retry")" == "v1 ${mode} $(stat -c '%d:%i' "${deployment}/.install-mode")" ]]
  local entry
  for entry in VERSION .env compose.yml current COMPATIBILITY_EPOCH release-manifest.json recovery; do
    [[ ! -e "${deployment}/${entry}" && ! -L "${deployment}/${entry}" ]]
  done
}

for spec in \
  'docker-pull docker 1' 'docker-health docker 1' \
  'host-prepare host 41' 'host-partial-release host 42' \
  'host-config host 43' 'host-health host 44' \
  'sigint docker 130' 'sigterm docker 143'; do
  read -r name mode expected <<<"${spec}"
  prepare_case "${name}"
  run_attempt "${name}" "${mode}" "${name}" "${expected}" first
  assert_retry_record "${name}" "${mode}"
  if [[ "${name}" == host-config || "${name}" == host-health ]]; then
    [[ -f "${TEST_WORK_DIR}/${name}/deployment/releases/1.2.3/backend/yunlume-backend.jar" ]]
  elif [[ "${name}" == host-partial-release ]]; then
    [[ -z "$(find "${TEST_WORK_DIR}/${name}/deployment/releases" -name '*.tmp.*' -print -quit)" ]]
  fi
  run_attempt "${name}" "${mode}" success 0 second
  [[ "$(cat "${TEST_WORK_DIR}/${name}/deployment/VERSION")" == 1.2.3 ]]
  [[ ! -e "${TEST_WORK_DIR}/${name}/deployment/.install-retry" ]]
  grep -Fxq INSTALL_SUCCESS "${TEST_WORK_DIR}/${name}/second.out"
done

# 连续失败必须重新发放许可；不能把一次已消费许可保留为永久绕过入口。
prepare_case repeated-failure
run_attempt repeated-failure docker docker-pull 1 first
run_attempt repeated-failure docker docker-pull 1 second
assert_retry_record repeated-failure docker
run_attempt repeated-failure docker success 0 third
[[ ! -e "${TEST_WORK_DIR}/repeated-failure/deployment/.install-retry" ]]

for spec in 'docker-rollback 1' 'term-rollback-failure 143' 'sigkill 137' 'cleanup-failure 1'; do
  read -r name expected <<<"${spec}"
  prepare_case "${name}"
  run_attempt "${name}" docker "${name}" "${expected}" first
  [[ ! -e "${TEST_WORK_DIR}/${name}/deployment/.install-retry" ]]
  if [[ "${name}" == docker-rollback || "${name}" == term-rollback-failure ]]; then
    [[ -n "$(find "${TEST_WORK_DIR}/${name}/deployment/recovery" -name failed.env -print -quit)" ]]
  fi
  run_attempt "${name}" docker success 1 second
  grep -Fq '已有托管部署缺少 VERSION' "${TEST_WORK_DIR}/${name}/second.out"
done

# 一个旧 mode 不代表本轮首次安装，残留元数据与篡改后的记录均必须拒绝。
prepare_case untracked-mode
mkdir -p "${TEST_WORK_DIR}/untracked-mode/deployment"
printf 'docker\n' >"${TEST_WORK_DIR}/untracked-mode/deployment/.install-mode"
run_attempt untracked-mode docker success 1 first
[[ ! -e "${TEST_WORK_DIR}/untracked-mode/deployment/.install-retry" ]]

for mutation in mode-replaced mode-changed record-public record-symlink state-file state-broken-link recovery; do
  prepare_case "${mutation}"
  run_attempt "${mutation}" docker docker-pull 1 first
  deployment="${TEST_WORK_DIR}/${mutation}/deployment"
  case "${mutation}" in
    mode-replaced)
      printf 'docker\n' >"${deployment}/new-mode"
      chmod 0644 "${deployment}/new-mode"
      mv -f -- "${deployment}/new-mode" "${deployment}/.install-mode"
      ;;
    mode-changed) printf 'host\n' >"${deployment}/.install-mode" ;;
    record-public) chmod 0644 "${deployment}/.install-retry" ;;
    record-symlink)
      mv -- "${deployment}/.install-retry" "${deployment}/saved-record"
      ln -s -- "${deployment}/saved-record" "${deployment}/.install-retry"
      ;;
    state-file) printf 'preserve\n' >"${deployment}/compose.yml" ;;
    state-broken-link) ln -s -- "${deployment}/missing" "${deployment}/current" ;;
    recovery) mkdir "${deployment}/recovery" ;;
  esac
  run_attempt "${mutation}" docker success 1 second
  [[ -e "${deployment}/.install-retry" || -L "${deployment}/.install-retry" ]]
  if [[ "${mutation}" == state-file ]]; then [[ "$(cat "${deployment}/compose.yml")" == preserve ]]; fi
  if [[ "${mutation}" == state-broken-link ]]; then [[ -L "${deployment}/current" ]]; fi
done

for artifact in app.env nginx.conf nginx-link service-file; do
  name="host-residue-${artifact}"
  prepare_case "${name}"
  run_attempt "${name}" host host-prepare 41 first
  assert_retry_record "${name}" host
  system_root="${TEST_WORK_DIR}/${name}/system"
  mkdir -p "${system_root}/etc/yunlume"
  case "${artifact}" in
    app.env|nginx.conf) residue="${system_root}/etc/yunlume/${artifact}" ;;
    nginx-link) residue="${system_root}/etc/nginx/conf.d/yunlume.conf" ;;
    service-file) residue="${system_root}/etc/systemd/system/yunlume-backend.service" ;;
  esac
  if [[ "${artifact}" == nginx-link ]]; then
    ln -s -- "${system_root}/missing" "${residue}"
  else
    printf 'preserve\n' >"${residue}"
  fi
  run_attempt "${name}" host success 1 second
  [[ -f "${TEST_WORK_DIR}/${name}/deployment/.install-retry" ]]
  if [[ "${artifact}" == nginx-link ]]; then
    [[ -L "${residue}" ]]
  else
    [[ "$(cat "${residue}")" == preserve ]]
  fi
done

printf 'First-install retries preserve cleanup, transaction, signal and state-identity boundaries.\n'
