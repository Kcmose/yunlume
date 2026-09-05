#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t yunlume-e2e-cleanup-test.XXXXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT
mkdir "${TEST_ROOT}/bin"

# 只移除命令分派，保留真实函数、错误处理和信号注册；不要求 root 或访问 Docker。
python3 - "${PROJECT_DIR}/ops/install-e2e.sh" "${TEST_ROOT}/e2e-lib.sh" <<'PY'
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text(encoding="utf-8")
entry = '\ncase "${1:-plan}" in\n'
assert source.count(entry) == 1
Path(sys.argv[2]).write_text(source.split(entry)[0] + "\n", encoding="utf-8")
PY

cat >"${TEST_ROOT}/bin/docker" <<'PY'
#!/usr/bin/env python3
import json
import os
import sys
import time
from pathlib import Path

case = Path(os.environ["E2E_TEST_CASE"])
state_file = case / "docker-state.json"
state = json.loads(state_file.read_text(encoding="utf-8"))
args = sys.argv[1:]
with (case / "docker-calls.jsonl").open("a", encoding="utf-8") as output:
    output.write(json.dumps(args) + "\n")
if len(args) < 2 or args[0] not in {"container", "network", "volume"}:
    raise SystemExit("Unexpected Docker boundary: " + repr(args))
kind, action = args[:2]
fault = state["fault"]
resources = state["resources"]

def fail(message, status=7):
    print(message, file=sys.stderr)
    raise SystemExit(status)

if action == "ls":
    expected = (["container", "ls", "--all", "--format", "{{json .Names}}"] if kind == "container"
                else [kind, "ls", "--format", "{{json .Name}}"])
    assert args == expected, args
    if state.get("delay_first_list") and not (case / "cleanup-ready").exists():
        (case / "cleanup-ready").touch()
        time.sleep(0.4)
    if fault == "list-failure" or (fault == "post-list-failure" and state.get("deleted")):
        fail("Cannot connect to the Docker daemon; plugin endpoint not found")
    if fault == "list-malformed":
        print("{invalid-json")
        raise SystemExit(0)
    for resource in resources:
        if resource["kind"] == kind:
            print(json.dumps(resource["name"]))
    if fault == "list-partial-failure":
        fail("Docker API failed after returning partial valid output")
    raise SystemExit(0)

if action == "inspect":
    assert len(args) == 5 and args[2] == "--format", args
    name = args[-1]
    selected = [r for r in resources if r["kind"] == kind and r["name"] == name]
    assert len(selected) == 1, selected
    resource = selected[0]
    label_path = ".Config.Labels" if kind == "container" else ".Labels"
    identity = ".Name" if kind == "volume" else ".Id"
    expected_format = '[{{json .Name}},{{json ' + identity + '}},{{json (index ' + label_path + ' "io.yunlume.install-e2e.run")}}]'
    assert args[3] == expected_format, args[3]
    if fault == "inspect-failure":
        fail("Docker inspect unavailable")
    if fault == "inspect-empty":
        raise SystemExit(0)
    reported_name = ("/" if kind == "container" else "") + name
    if fault == "inspect-wrong-name":
        reported_name += "-different"
    print(json.dumps([reported_name, resource["id"], resource["label"]]))
    if fault == "inspect-partial-failure":
        fail("Docker inspect failed after returning valid stdout")
    raise SystemExit(0)

if action == "rm":
    assert args[:3] == ["container", "rm", "--force"] if kind == "container" else args[:2] == [kind, "rm"]
    assert len(args) == (4 if kind == "container" else 3), args
    identifier = args[-1]
    selected = [r for r in resources if r["kind"] == kind and r["id"] == identifier]
    assert len(selected) == 1, ("Deletion did not use the verified exact identity", args)
    resource = selected[0]
    assert resource["label"] == state["run_id"], "Foreign resource mutation"
    if fault == "delete-failure":
        fail("Docker removal failed", 8)
    if fault != "delete-noop":
        resources.remove(resource)
        state["deleted"] = True
    if fault == "replacement":
        replacement = dict(resource, label="different-run", id=("f" * 64 if kind != "volume" else resource["name"]))
        resources.append(replacement)
    state_file.write_text(json.dumps(state), encoding="utf-8")
    print(identifier)
    if fault == "delete-partial-failure":
        fail("Docker removal returned a nonzero result", 8)
    raise SystemExit(0)

raise SystemExit("Unexpected Docker mutation: " + repr(args))
PY
chmod 0700 "${TEST_ROOT}/bin/docker"

cat >"${TEST_ROOT}/driver.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
source "$1/e2e-lib.sh"
action="$2" context="$3" kind="$4" initial_status="$5"
prepare_state_root
derive_resource_names cleanup-case-0001
install -d -m 0700 "${RUN_DIR}"
write_manifest
case "${kind}" in
  container) target="${BACKEND_CONTAINER}" ;;
  network) target="${NETWORK_NAME}" ;;
  volume) target="${CONFIG_VOLUME}" ;;
esac
rm() {
  printf '%s\n' "$*" >>"${E2E_TEST_CASE}/directory-removals"
  if [[ -f "${E2E_TEST_CASE}/directory-failure" ]]; then return 9; fi
  command rm "$@"
}
invoke() {
  case "${action}" in
    remove) "remove_owned_${kind}" "${target}" ;;
    unused) assert_resource_names_unused ;;
    batch) cleanup_resources ;;
  esac
}
case "${action}" in
  remove|unused|batch)
    if [[ "${context}" == conditional ]]; then
      if invoke; then exit 0; else exit "$?"; fi
    else
      invoke
    fi
    ;;
  prepare) : ;;
  exit|signal)
    register_exit_handlers
    RUN_INITIALIZED=true
    if [[ -f "${E2E_TEST_CASE}/directory-symlink" ]]; then
      mv -- "${RUN_DIR}" "${RUN_DIR}.saved"
      ln -s -- "${RUN_DIR}.saved" "${RUN_DIR}"
    fi
    if [[ "${action}" == signal ]]; then
      : >"${E2E_TEST_CASE}/ready"
      # 真正阻塞于 Bash read，由父进程发送信号；不靠调用 trap 函数伪造中断。
      IFS= read -r -t 20 interrupted_input
      exit 99
    fi
    exit "${initial_status}"
    ;;
  *) exit 98 ;;
esac
SH

python3 - "${PROJECT_DIR}" "${TEST_ROOT}" <<'PY'
import hashlib
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

project, root = map(Path, sys.argv[1:])
run_id = "cleanup-case-0001"
prefix = "yunlume-e2e-" + run_id
targets = {"container": prefix + "-backend", "network": prefix + "-net", "volume": prefix + "-config"}
owned_names = [("container", prefix + suffix) for suffix in ("-backend", "-redis", "-postgres")]
owned_names += [("network", prefix + "-net")]
owned_names += [("volume", prefix + suffix) for suffix in ("-config", "-uploads", "-logs")]
checks = 0

def resource(kind, name, label):
    return {"kind": kind, "name": name, "label": label,
            "id": name if kind == "volume" else hashlib.sha256((kind + name).encode()).hexdigest()}

def prepare(name, kind="container", owned="single", fault="none", foreign=False, delay=False):
    case = root / name
    case.mkdir()
    resources = [resource(k, "unrelated-" + k, "another-run") for k in targets]
    selected = owned_names if owned == "all" else ([(kind, targets[kind])] if owned == "single" else [])
    resources += [resource(k, n, "another-run" if foreign else run_id) for k, n in selected]
    (case / "docker-state.json").write_text(json.dumps({
        "run_id": run_id, "resources": resources, "fault": fault, "delay_first_list": delay,
    }), encoding="utf-8")
    env = dict(os.environ, E2E_STATE_ROOT=str(case / "state"), E2E_TEST_CASE=str(case),
               PATH=str(root / "bin") + os.pathsep + os.environ["PATH"])
    return case, env

def driver(case, env, action, context="bare", kind="container", initial=0, expected=0):
    result = subprocess.run(["bash", str(root / "driver.sh"), str(root), action, context, kind, str(initial)],
                            env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
    (case / "output").write_text(result.stdout, encoding="utf-8")
    assert result.returncode == expected, (case.name, result.returncode, expected, result.stdout)
    return result.stdout

def state(case):
    return json.loads((case / "docker-state.json").read_text(encoding="utf-8"))

def calls(case):
    path = case / "docker-calls.jsonl"
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.exists() else []

def run_dir(case):
    return case / "state" / run_id

def assert_foreign_untouched(case):
    remaining = state(case)["resources"]
    for kind in targets:
        assert resource(kind, "unrelated-" + kind, "another-run") in remaining, case.name

def assert_retained(case):
    assert (run_dir(case) / "resource-manifest").is_file(), case.name
    assert "资源已精确清理" not in (case / "output").read_text(encoding="utf-8"), case.name

for kind in targets:
    for context in ("bare", "conditional"):
        for fault in ("none", "absent", "foreign", "list-failure", "list-partial-failure", "list-malformed",
                      "inspect-failure", "inspect-partial-failure", "inspect-empty", "inspect-wrong-name",
                      "delete-failure", "delete-partial-failure", "post-list-failure", "delete-noop", "replacement"):
            case, env = prepare(f"remove-{kind}-{context}-{fault}", kind,
                                owned="none" if fault == "absent" else "single",
                                fault=fault, foreign=fault == "foreign")
            driver(case, env, "remove", context, kind, expected=0 if fault in {"none", "absent"} else 1)
            mutation = [args for args in calls(case) if args[1] == "rm"]
            if fault in {"none", "delete-failure", "delete-partial-failure", "post-list-failure", "delete-noop", "replacement"}:
                expected_id = resource(kind, targets[kind], run_id)["id"]
                assert len(mutation) == 1 and mutation[0][-1] == expected_id, (case.name, mutation)
            else:
                assert not mutation, (case.name, mutation)
            if fault in {"none", "absent"}:
                assert not any(r["name"] == targets[kind] for r in state(case)["resources"]), case.name
            if fault == "replacement":
                assert any(r["name"] == targets[kind] and r["label"] == "different-run" for r in state(case)["resources"]), case.name
            assert_foreign_untouched(case)
            checks += 1

for context in ("bare", "conditional"):
    for fault in ("absent", "owned", "foreign", "list-failure", "list-partial-failure", "list-malformed"):
        case, env = prepare(f"unused-{context}-{fault}", owned="single" if fault in {"owned", "foreign"} else "none",
                            fault=fault, foreign=fault == "foreign")
        driver(case, env, "unused", context, expected=0 if fault == "absent" else 1)
        assert all(args[1] == "ls" for args in calls(case)), case.name
        assert_foreign_untouched(case)
        checks += 1

for action in ("batch", "exit", "cli"):
    for fault in ("none", "absent", "foreign", "list-failure", "list-partial-failure", "inspect-partial-failure",
                  "delete-failure", "post-list-failure"):
        case, env = prepare(f"{action}-{fault}", owned="none" if fault == "absent" else "all",
                            fault=fault, foreign=fault == "foreign")
        expected = 0 if fault in {"none", "absent"} else 1
        if action == "cli":
            driver(case, env, "prepare")
            env["CONFIRM_ISOLATED_INSTALL_E2E_CLEANUP"] = "CLEAN-ISOLATED-INSTALL-E2E"
            result = subprocess.run(["bash", str(project / "ops/install-e2e.sh"), "cleanup", run_id],
                                    env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
            (case / "output").write_text(result.stdout, encoding="utf-8")
            assert result.returncode == expected, (case.name, result.returncode, result.stdout)
        else:
            driver(case, env, action, context="conditional", expected=expected)
        assert_foreign_untouched(case)
        if action != "batch":
            if expected == 0:
                assert not run_dir(case).exists(), case.name
                assert "资源已精确清理" in (case / "output").read_text(encoding="utf-8"), case.name
            else:
                assert_retained(case)
            # 空资源每类只查询一次/精确名称，证明统一 EXIT 没有重复清理。
            if fault == "absent":
                assert len(calls(case)) == 7, (case.name, calls(case))
        checks += 1

for initial in (0, 7):
    for fault in ("none", "list-failure", "directory-failure", "directory-symlink"):
        case, env = prepare(f"exit-code-{initial}-{fault}", owned="all", fault=fault)
        if fault.startswith("directory-"):
            (case / fault).touch()
        expected = initial or (1 if fault != "none" else 0)
        driver(case, env, "exit", initial=initial, expected=expected)
        if fault == "none":
            assert not run_dir(case).exists(), case.name
        else:
            assert_retained(case)
        assert_foreign_untouched(case)
        checks += 1

def wait_file(path, process):
    deadline = time.monotonic() + 8
    while not path.exists():
        assert process.poll() is None, (path, process.communicate()[0])
        assert time.monotonic() < deadline, path
        time.sleep(0.01)

for sig, expected in ((signal.SIGINT, 130), (signal.SIGTERM, 143)):
    for fault in ("none", "list-failure", "directory-failure"):
        case, env = prepare(f"signal-{sig.name}-{fault}", owned="all", fault=fault, delay=True)
        if fault == "directory-failure":
            (case / fault).touch()
        process = subprocess.Popen(["bash", str(root / "driver.sh"), str(root), "signal", "bare", "container", "0"],
                                   env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            wait_file(case / "ready", process)
            process.send_signal(sig)
            wait_file(case / "cleanup-ready", process)
            process.send_signal(signal.SIGTERM if sig == signal.SIGINT else signal.SIGINT)
            output, _ = process.communicate(timeout=12)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()
        (case / "output").write_text(output, encoding="utf-8")
        assert process.returncode == expected, (case.name, process.returncode, output)
        if fault == "none":
            assert not run_dir(case).exists(), case.name
            assert len([args for args in calls(case) if args[1] == "rm"]) == 7, case.name
        else:
            assert_retained(case)
        if fault == "list-failure":
            assert len(calls(case)) == 7, (case.name, calls(case))
        assert_foreign_untouched(case)
        checks += 1

print(f"Install E2E cleanup: {checks} real Bash boundary, EXIT and asynchronous signal scenarios passed.")
PY
