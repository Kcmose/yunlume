#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t yunlume-installer-rollback-state.XXXXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

# 调用真实状态解析、Host/Docker 恢复及 EXIT 清理；所有路径均在临时目录，外部命令为可控替身。
python3 - "${PROJECT_DIR}" "${TEST_ROOT}" <<'PY'
import subprocess
import sys
from pathlib import Path

project, root = map(Path, sys.argv[1:])
source = (project / "install.sh").read_text(encoding="utf-8")
entry = '\nmain "$@"\n'
assert source.count(entry) == 1, "installer entry point changed"
library = root / "install-lib.sh"
library.write_text(source.replace(entry, "\n"), encoding="utf-8")

fixture = r'''
set -Eeuo pipefail
source "$1"
trap - EXIT ERR INT TERM
CASE_DIR="$2"
INSTALL_DIR="${CASE_DIR}/deployment"
WORK_DIR="${CASE_DIR}/work"
mkdir -p "${INSTALL_DIR}" "${WORK_DIR}"
printf 'manifest\n' >"${WORK_DIR}/release-manifest.json"
CALLS="${CASE_DIR}/calls"
touch "${CALLS}"
FAIL_CP="" FAIL_MV=false FAIL_SYSTEMCTL="" FAIL_DOCKER=false FAIL_HEALTH=false FAIL_ARCHIVE=false
SHOW_EXIT=0
SHOW_OUTPUT=$'LoadState=loaded\nActiveState=active\nUnitFileState=enabled'
systemctl() {
  printf 'systemctl %s\n' "$*" >>"${CALLS}"
  if [[ "$1" == show ]]; then printf '%s\n' "${SHOW_OUTPUT}"; return "${SHOW_EXIT}"; fi
  if [[ -n "${FAIL_SYSTEMCTL}" && "$*" == "${FAIL_SYSTEMCTL}" ]]; then return 71; fi
}
docker() {
  printf 'docker %s\n' "$*" >>"${CALLS}"
  [[ "${FAIL_DOCKER}" != true ]] || return 72
}
cp() {
  if [[ -n "${FAIL_CP}" && "$*" == *"${FAIL_CP}"* ]]; then return 73; fi
  if [[ "${FAIL_ARCHIVE}" == true && "$*" == *"${INSTALL_DIR}/recovery/"* ]]; then return 74; fi
  command cp "$@"
}
mv() { [[ "${FAIL_MV}" != true ]] || return 75; command mv "$@"; }
nginx() { printf 'nginx %s\n' "$*" >>"${CALLS}"; }
wait_for_http() { [[ "${FAIL_HEALTH}" != true ]]; }
wait_for_url() { [[ "${FAIL_HEALTH}" != true ]]; }
wait_for_host_pending_install() { [[ "${FAIL_HEALTH}" != true ]]; }
prepare_host() {
  HOST_CURRENT_LINK="${INSTALL_DIR}/current"
  HOST_PREVIOUS_CURRENT="${INSTALL_DIR}/previous-release"
  mkdir -p "${HOST_PREVIOUS_CURRENT}" "${INSTALL_DIR}/new-release"
  ln -s "${INSTALL_DIR}/new-release" "${HOST_CURRENT_LINK}"
  HOST_HAD_CURRENT=true
  HOST_NGINX_CONFIG="${INSTALL_DIR}/nginx.conf"
  HOST_NGINX_BACKUP="${WORK_DIR}/previous.nginx.conf"
  HOST_HAD_NGINX_CONFIG=true
  HOST_SERVICE_FILE="${INSTALL_DIR}/backend.service"
  HOST_SERVICE_BACKUP="${WORK_DIR}/previous.service"
  HOST_HAD_SERVICE_FILE=true
  HOST_VERSION_FILE="${INSTALL_DIR}/VERSION"
  HOST_VERSION_BACKUP="${WORK_DIR}/previous.VERSION"
  HOST_HAD_VERSION=true
  HOST_MANIFEST_FILE="${INSTALL_DIR}/release-manifest.json"
  HOST_MANIFEST_BACKUP="${WORK_DIR}/previous.release-manifest.json"
  HOST_HAD_MANIFEST=true
  HOST_COMPATIBILITY_EPOCH_FILE="${INSTALL_DIR}/COMPATIBILITY_EPOCH"
  HOST_COMPATIBILITY_EPOCH_BACKUP="${WORK_DIR}/previous.COMPATIBILITY_EPOCH"
  HOST_HAD_COMPATIBILITY_EPOCH=true
  HOST_APP_ENV_FILE="${INSTALL_DIR}/app.env"
  HOST_APP_ENV_BACKUP="${WORK_DIR}/previous.app.env"
  HOST_HAD_APP_ENV=true
  HOST_NGINX_LINK="${INSTALL_DIR}/nginx.link"
  HOST_PREVIOUS_NGINX_LINK="${HOST_NGINX_CONFIG}"
  HOST_HAD_NGINX_LINK=true
  ln -s "${INSTALL_DIR}/new-nginx.conf" "${HOST_NGINX_LINK}"
  HOST_BACKEND_MUTATED=true HOST_NGINX_MUTATED=true
  HOST_SERVICE_WAS_ACTIVE=true HOST_SERVICE_WAS_ENABLED=true
  HOST_NGINX_WAS_ACTIVE=true HOST_NGINX_WAS_ENABLED=true
  local variable
  for variable in HOST_NGINX_CONFIG HOST_SERVICE_FILE HOST_VERSION_FILE HOST_MANIFEST_FILE HOST_COMPATIBILITY_EPOCH_FILE HOST_APP_ENV_FILE; do
    printf 'new\n' >"${!variable}"
  done
  for variable in HOST_NGINX_BACKUP HOST_SERVICE_BACKUP HOST_VERSION_BACKUP HOST_MANIFEST_BACKUP HOST_COMPATIBILITY_EPOCH_BACKUP HOST_APP_ENV_BACKUP; do
    printf 'old\n' >"${!variable}"
  done
}
assert_host_restored() {
  [[ "$(readlink "${HOST_CURRENT_LINK}")" == "${HOST_PREVIOUS_CURRENT}" ]]
  [[ "$(readlink "${HOST_NGINX_LINK}")" == "${HOST_PREVIOUS_NGINX_LINK}" ]]
  local variable
  for variable in HOST_NGINX_CONFIG HOST_SERVICE_FILE HOST_VERSION_FILE HOST_MANIFEST_FILE HOST_COMPATIBILITY_EPOCH_FILE HOST_APP_ENV_FILE; do
    [[ "$(cat "${!variable}")" == old ]]
  done
}
assert_host_not_restarted() {
  ! grep -Eq '^systemctl (daemon-reload|enable|restart|reload)( |$)' "${CALLS}"
  ! grep -q '^nginx ' "${CALLS}"
}
prepare_docker() {
  DOCKER_ENV_FILE="${INSTALL_DIR}/.env"
  DOCKER_COMPOSE_FILE="${INSTALL_DIR}/compose.yml"
  DOCKER_VERSION_FILE="${INSTALL_DIR}/VERSION"
  DOCKER_MANIFEST_FILE="${INSTALL_DIR}/release-manifest.json"
  DOCKER_COMPATIBILITY_EPOCH_FILE="${INSTALL_DIR}/COMPATIBILITY_EPOCH"
  DOCKER_ENV_BACKUP="${WORK_DIR}/previous.env"
  DOCKER_COMPOSE_BACKUP="${WORK_DIR}/previous.compose.yml"
  DOCKER_VERSION_BACKUP="${WORK_DIR}/previous.VERSION"
  DOCKER_MANIFEST_BACKUP="${WORK_DIR}/previous.release-manifest.json"
  DOCKER_COMPATIBILITY_EPOCH_BACKUP="${WORK_DIR}/previous.COMPATIBILITY_EPOCH"
  DOCKER_HAD_ENV=true DOCKER_HAD_COMPOSE=true DOCKER_HAD_VERSION=true
  DOCKER_HAD_MANIFEST=true DOCKER_HAD_COMPATIBILITY_EPOCH=true
  DOCKER_SERVICES_MUTATED=true
  local item file backup
  for item in ENV COMPOSE VERSION MANIFEST COMPATIBILITY_EPOCH; do
    file="DOCKER_${item}_FILE" backup="DOCKER_${item}_BACKUP"
    printf 'new\n' >"${!file}"
    printf 'old\n' >"${!backup}"
  done
  printf 'failed\n' >"${WORK_DIR}/failed.env"
  printf 'failed\n' >"${WORK_DIR}/failed.compose.yml"
}
assert_docker_restored() {
  local item file
  for item in ENV COMPOSE VERSION MANIFEST COMPATIBILITY_EPOCH; do
    file="DOCKER_${item}_FILE"
    [[ "$(cat "${!file}")" == old ]]
  done
}
'''

checks = 0

def run(name, body, expected=0):
    global checks
    case = root / name
    case.mkdir()
    script = case / "attempt.sh"
    script.write_text(fixture + "\n" + body + "\n", encoding="utf-8")
    result = subprocess.run(["bash", str(script), str(library), str(case)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
    (case / "output.log").write_text(result.stdout, encoding="utf-8")
    assert result.returncode == expected, (name, result.returncode, result.stdout)
    checks += 1
    return case

for key, target in {
    "NGINX": "HOST_NGINX_CONFIG", "SERVICE": "HOST_SERVICE_FILE", "VERSION": "HOST_VERSION_FILE",
    "MANIFEST": "HOST_MANIFEST_FILE", "COMPATIBILITY_EPOCH": "HOST_COMPATIBILITY_EPOCH_FILE",
    "APP_ENV": "HOST_APP_ENV_FILE",
}.items():
    run("host-missing-" + key.lower(), f'''
prepare_host
rm -- "${{HOST_{key}_BACKUP}}"
if rollback_host; then exit 91; fi
[[ "$(cat "${{{target}}}")" == new ]]
assert_host_not_restarted
printf 'old\\n' >"${{HOST_{key}_BACKUP}}"
rollback_host
assert_host_restored
rollback_host
assert_host_restored
''')

run("host-missing-release", r'''
prepare_host
rmdir -- "${HOST_PREVIOUS_CURRENT}"
if rollback_host; then exit 91; fi
[[ "$(readlink "${HOST_CURRENT_LINK}")" == "${INSTALL_DIR}/new-release" ]]
assert_host_not_restarted
mkdir -- "${HOST_PREVIOUS_CURRENT}"
rollback_host
assert_host_restored
''')

for label, failure in {
    "copy": 'FAIL_CP="${HOST_APP_ENV_BACKUP}"', "link-commit": 'FAIL_MV=true',
    "reload": 'FAIL_SYSTEMCTL="daemon-reload"',
    "restart": 'FAIL_SYSTEMCTL="restart yunlume-backend.service"',
    "stop": 'FAIL_SYSTEMCTL="stop yunlume-backend.service"', "health": 'FAIL_HEALTH=true',
}.items():
    no_restart = "assert_host_not_restarted" if label in {"copy", "link-commit", "stop"} else ":"
    if label == "reload":
        no_restart = '! grep -Eq \'^systemctl (enable|restart|reload)( |$)\' "${CALLS}"'
    run("host-command-" + label, f'''
prepare_host
{failure}
if rollback_host; then exit 91; fi
[[ -f "${{HOST_APP_ENV_BACKUP}}" ]]
{no_restart}
FAIL_CP="" FAIL_MV=false FAIL_SYSTEMCTL="" FAIL_HEALTH=false
rollback_host
assert_host_restored
''')

run("host-success", "prepare_host\nrollback_host\nassert_host_restored\nrollback_host\nassert_host_restored")
run("host-originally-absent", r'''
prepare_host
HOST_HAD_CURRENT=false HOST_HAD_NGINX_CONFIG=false HOST_HAD_SERVICE_FILE=false HOST_HAD_NGINX_LINK=false
HOST_HAD_VERSION=false HOST_HAD_MANIFEST=false HOST_HAD_COMPATIBILITY_EPOCH=false HOST_HAD_APP_ENV=false
HOST_SERVICE_WAS_ACTIVE=false HOST_SERVICE_WAS_ENABLED=false HOST_NGINX_WAS_ACTIVE=false HOST_NGINX_WAS_ENABLED=false
rollback_host
for path in "${HOST_CURRENT_LINK}" "${HOST_NGINX_CONFIG}" "${HOST_SERVICE_FILE}" "${HOST_VERSION_FILE}" "${HOST_MANIFEST_FILE}" "${HOST_COMPATIBILITY_EPOCH_FILE}" "${HOST_APP_ENV_FILE}"; do
  [[ ! -e "${path}" && ! -L "${path}" ]]
done
''')

for key in ("ENV", "COMPOSE", "VERSION", "MANIFEST", "COMPATIBILITY_EPOCH"):
    run("docker-missing-" + key.lower(), f'''
prepare_docker
rm -- "${{DOCKER_{key}_BACKUP}}"
if rollback_docker; then exit 91; fi
[[ "$(cat "${{DOCKER_{key}_FILE}}")" == new ]]
! grep -q '^docker ' "${{CALLS}}"
printf 'old\\n' >"${{DOCKER_{key}_BACKUP}}"
rollback_docker
assert_docker_restored
rollback_docker
assert_docker_restored
''')

for missing in ("failed.env", "failed.compose.yml", "both"):
    removals = 'rm -- "${WORK_DIR}/failed.env" "${WORK_DIR}/failed.compose.yml"' if missing == "both" else f'rm -- "${{WORK_DIR}}/{missing}"'
    run("docker-fresh-missing-" + missing, f'''
prepare_docker
DOCKER_HAD_ENV=false DOCKER_HAD_COMPOSE=false DOCKER_HAD_VERSION=false DOCKER_HAD_MANIFEST=false DOCKER_HAD_COMPATIBILITY_EPOCH=false
{removals}
if rollback_docker; then exit 91; fi
! grep -q '^docker ' "${{CALLS}}"
printf 'failed\\n' >"${{WORK_DIR}}/failed.env"
printf 'failed\\n' >"${{WORK_DIR}}/failed.compose.yml"
rollback_docker
grep -q 'down --remove-orphans' "${{CALLS}}"
''')

for label, failure in {"compose": "FAIL_DOCKER=true", "health": "FAIL_HEALTH=true"}.items():
    run("docker-command-" + label, f'''
prepare_docker
{failure}
if rollback_docker; then exit 91; fi
[[ -f "${{DOCKER_ENV_BACKUP}}" ]]
FAIL_DOCKER=false FAIL_HEALTH=false
rollback_docker
assert_docker_restored
''')
run("docker-success", "prepare_docker\nrollback_docker\nassert_docker_restored\nrollback_docker\nassert_docker_restored")
run("docker-unchanged-services", r'''
prepare_docker
DOCKER_SERVICES_MUTATED=false
rm -- "${WORK_DIR}/failed.env" "${WORK_DIR}/failed.compose.yml"
rollback_docker
assert_docker_restored
! grep -q '^docker ' "${CALLS}"
''')

# 清理失败必须改变退出码；归档失败时原工作目录不能被删除。
for mode in ("host", "docker"):
    for archive_failure in (False, True):
        body = f'prepare_{mode}\n{mode.upper()}_TRANSACTION_ACTIVE=true\n'
        body += 'FAIL_SYSTEMCTL="restart yunlume-backend.service"\n' if mode == "host" else 'FAIL_DOCKER=true\n'
        body += f'FAIL_ARCHIVE={str(archive_failure).lower()}\ntrue\ncleanup\n'
        case = run(f"{mode}-cleanup-archive-{archive_failure}", body, expected=1)
        recovery = case / "deployment/recovery"
        if archive_failure:
            assert (case / "work").is_dir(), (mode, "original recovery material was deleted")
        else:
            assert not (case / "work").exists(), (mode, "archived workspace unexpectedly kept")
            assert list(recovery.glob("rollback.*/previous.*")), (mode, "recovery archive lost backups")

# 成功返回的已知状态才可形成快照；有效 stdout 不能覆盖外部命令的非零退出。
states = [
    ("loaded", "active", "enabled", "true true"),
    ("loaded", "inactive", "disabled", "false false"),
    ("not-found", "inactive", "", "false false"),
    ("masked", "inactive", "masked", "false false"),
    ("loaded", "failed", "static", "false true"),
    ("loaded", "reloading", "enabled-runtime", "true true"),
]
states += [("loaded", "inactive", value, "false true") for value in ("alias", "indirect", "generated")]
states += [("loaded", "inactive", value, "false false") for value in ("linked", "linked-runtime", "masked-runtime", "transient", "bad")]
for index, (load, active, unit_file, expected) in enumerate(states):
    run(f"systemd-known-{index}", f'''
SHOW_OUTPUT=$'LoadState={load}\\nActiveState={active}\\nUnitFileState={unit_file}'
[[ "$(read_systemd_rollback_state yunlume-backend.service)" == '{expected}' ]]
''')
for index, bad in enumerate(("", "LoadState=loaded", "LoadState=loaded\nActiveState=active",
        "LoadState=loaded\nActiveState=active\nUnitFileState=unknown",
        "LoadState=unknown\nActiveState=inactive\nUnitFileState=disabled",
        "LoadState=loaded\nActiveState=unknown\nUnitFileState=enabled",
        "LoadState=not-found\nActiveState=active\nUnitFileState=",
        "LoadState=loaded\nActiveState=active\nUnitFileState=enabled\nActiveState=inactive")):
    encoded = bad.replace("\n", "\\n")
    run(f"systemd-invalid-{index}", f'''
SHOW_OUTPUT=$'{encoded}'
if read_systemd_rollback_state yunlume-backend.service; then exit 91; fi
SHOW_OUTPUT=$'LoadState=loaded\\nActiveState=active\\nUnitFileState=enabled'
[[ "$(read_systemd_rollback_state yunlume-backend.service)" == 'true true' ]]
''')
for code in (1, 3, 4, 71):
    run(f"systemd-query-failure-{code}", f'''
SHOW_EXIT={code}
if read_systemd_rollback_state yunlume-backend.service; then exit 91; fi
SHOW_OUTPUT=$'LoadState=not-found\\nActiveState=inactive\\nUnitFileState='
if read_systemd_rollback_state yunlume-backend.service; then exit 91; fi
SHOW_EXIT=0
[[ "$(read_systemd_rollback_state yunlume-backend.service)" == 'false false' ]]
''')

assert 'backend_state="$(read_systemd_rollback_state yunlume-backend.service)" ||' in source
assert 'nginx_state="$(read_systemd_rollback_state nginx.service)" ||' in source
print(f"Installer rollback state: {checks} fault, recovery, cleanup and systemd snapshot scenarios passed.")
PY
