#!/usr/bin/env bash
set -Eeuo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly TEST_DIR="$(mktemp -d "${ROOT_DIR}/.migration-signal-test.XXXXXXXX")"
trap 'rm -rf -- "${TEST_DIR}"' EXIT

# 运行真实清理函数与 EXIT/INT/TERM 入口；只替换外部 Docker CLI。
# 独立资源状态和 canary 验证实际结果，不相信清理函数自报的 residue。
python3 - "${ROOT_DIR}" "${TEST_DIR}" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

root, lab = map(Path, sys.argv[1:])
source = (root / "ops/postgresql-migration-test.sh").read_text()
end = "trap 'handle_signal 143' TERM\n"
assert source.count(end) == 1
prefix = source.split(end, 1)[0] + end
fixture = lab / "fixture.jar"
fixture.write_bytes(b"signal fixture\n")
digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
bin_dir = lab / "bin"
bin_dir.mkdir()
docker = bin_dir / "docker"
docker.write_text(r'''#!/usr/bin/env python3
import json, os, sys, time
from pathlib import Path
state_file = Path(os.environ["MIGRATION_TEST_STATE"])
args = sys.argv[1:]
mode = os.environ["MIGRATION_TEST_FAULT"]
fault_kind = os.environ["MIGRATION_TEST_KIND"]
if args[0] == "__fixture":
    project, pg, network, image, temporary = args[1:]
    resources = []
    for kind, name, own in [("container", project + "-app", True), ("container", pg, True),
                            ("network", network, True), ("image", image, True),
                            ("container", project + "-unregistered", False),
                            ("network", project + "-unregistered-net", False),
                            ("image", "canary:untouched", False)]:
        identity = ("sha256:" if kind == "image" else "") + f"{len(resources) + 1:064x}"
        resources.append(dict(kind=kind, name=name, id=identity, label=project, owned=own))
    if mode == "absent":
        resources = [r for r in resources if not (r["owned"] and r["kind"] == fault_kind)]
    if mode == "foreign":
        for r in resources:
            if r["owned"] and r["kind"] == fault_kind:
                r["label"] = "another-run"
    state_file.write_text(json.dumps(dict(resources=resources, initial=resources.copy(), calls=[],
                                         removals=[], project=project, temporary=temporary)))
    raise SystemExit(0)
state = json.loads(state_file.read_text())
state["calls"].append(args)
def finish(code=0):
    state_file.write_text(json.dumps(state))
    raise SystemExit(code)
kind, command = args[:2]
fault = kind == fault_kind
if command == "ls":
    if fault and mode in ("query", "query-partial", "query-malformed", "query-nonstring"):
        if mode == "query-partial":
            print('"apparently-empty"')
        elif mode == "query-malformed":
            print("{")
            finish()
        elif mode == "query-nonstring":
            print("42")
            finish()
        finish(7)
    if fault and mode == "post-query" and any(r[0] == kind for r in state["removals"]):
        finish(7)
    for r in state["resources"]:
        if r["kind"] == kind:
            if kind == "image":
                repository, tag = r["name"].rsplit(":", 1)
                print(json.dumps(repository) + ":" + json.dumps(tag))
            else:
                print(json.dumps(r["name"]))
    finish()
if command == "inspect":
    matches = [r for r in state["resources"] if r["kind"] == kind and r["name"] == args[-1]]
    if len(matches) != 1:
        finish(8)
    r = matches[0]
    name = [r["name"]] if kind == "image" else (("/" if kind == "container" else "") + r["name"])
    identity = r["id"]
    if fault and mode == "identity":
        identity = "invalid-id"
    if fault and mode == "name":
        name = ["different:tag"] if kind == "image" else "different-name"
    if fault and mode == "extra-tag" and kind == "image":
        name.append("foreign:reference")
    if fault and mode == "inspect-malformed":
        print("{")
    elif not (fault and mode == "inspect"):
        print(json.dumps([name, identity, r["label"]]))
    finish(7 if fault and mode in ("inspect", "inspect-partial") else 0)
if command == "rm":
    matches = [r for r in state["resources"] if r["kind"] == kind and r["id"] == args[-1]]
    assert len(matches) == 1, ("delete must use immutable ID", args)
    r = matches[0]
    assert r["owned"] and r["label"] == state["project"], ("canary/foreign deletion", args)
    assert args == ([kind, "rm", "-f", r["id"]] if kind == "container" else [kind, "rm", r["id"]])
    if mode == "repeat" and not state["removals"]:
        Path(str(state_file) + ".cleaning").touch()
        deadline = time.monotonic() + 10
        while not Path(str(state_file) + ".continue").exists():
            assert time.monotonic() < deadline, "cleanup continuation timeout"
            time.sleep(0.01)
    state["removals"].append([kind, r["id"]])
    if fault and mode == "remove":
        finish(9)
    if not (fault and mode == "noop"):
        state["resources"].remove(r)
    if fault and mode == "replacement":
        state["resources"].append(dict(r, id=("sha256:" if kind == "image" else "") + "f" * 64, label="another-run"))
    finish()
raise SystemExit("unexpected Docker call: " + repr(args))
''')
docker.chmod(0o755)
harness = lab / "harness.sh"
harness.write_text(prefix + r'''
register_container "${PROJECT}-app"
docker __fixture "${PROJECT}" "${PG}" "${NETWORK}" "${IMAGE}" "${TMP_DIR}"
# 等待子进程完成启动；fork 后立刻退出可能把 TERM 发到尚在初始化信号的子 Bash。
python3 - "${MIGRATION_TEST_STATE}.child-ready" <<'CHILD' &
from pathlib import Path
import signal
import sys
import time
signal.signal(signal.SIGTERM, signal.SIG_DFL)
Path(sys.argv[1]).touch()
time.sleep(60)
CHILD
child_pids+=("$!")
printf '%s\n' "$!" >"${MIGRATION_TEST_STATE}.child"
for _ in {1..1000}; do
  [[ -e "${MIGRATION_TEST_STATE}.child-ready" ]] && break
  kill -0 "${child_pids[0]}" 2>/dev/null || exit 1
  sleep 0.01
done
[[ -e "${MIGRATION_TEST_STATE}.child-ready" ]] || { printf 'child startup timed out\n' >&2; exit 1; }
printf 'ready\n'
if [[ "${MIGRATION_TEST_EXIT}" == signal ]]; then
  while :; do IFS= read -r -t 3600 unused || :; done
else
  exit "${MIGRATION_TEST_EXIT}"
fi
''')
harness.chmod(0o755)

checks = 0
def wait_for(condition, process, description):
    deadline = time.monotonic() + 10
    while not condition():
        assert process.poll() is None, f"{description}: exited {process.returncode}"
        assert time.monotonic() < deadline, f"{description}: timed out"
        time.sleep(0.01)

def run(kind, mode, incoming):
    global checks
    case = f"{kind}-{mode}-{incoming}"
    state_file = lab / (case + ".json")
    output = lab / (case + ".log")
    env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
               MIGRATION_TEST_STATE=str(state_file), MIGRATION_TEST_FAULT=mode,
               MIGRATION_TEST_KIND=kind, MIGRATION_TEST_EXIT="signal" if isinstance(incoming, str) else str(incoming))
    process = None
    try:
        with output.open("w") as log:
            process = subprocess.Popen([str(harness), str(fixture), digest], env=env,
                                       stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
            if isinstance(incoming, str):
                wait_for(lambda: "ready\n" in output.read_text(), process, case)
                process.send_signal(getattr(signal, "SIG" + incoming))
                if mode == "repeat":
                    wait_for(lambda: Path(str(state_file) + ".cleaning").exists(), process, case + " cleanup")
                    process.send_signal(signal.SIGTERM if incoming == "INT" else signal.SIGINT)
                    Path(str(state_file) + ".continue").touch()
            status = process.wait(timeout=20)
        text = output.read_text()
        clean = mode in ("none", "absent", "repeat")
        expected = {"INT": 130, "TERM": 143}.get(incoming, incoming) or (0 if clean else 1)
        assert status == expected, (case, status, expected, text)
        state = json.loads(state_file.read_text())
        initial_canaries = [r for r in state["initial"] if not r["owned"]]
        assert [r for r in state["resources"] if not r["owned"]] == initial_canaries, case + " canary changed"
        assert text.count("PostgreSQL migration cleanup:") == 1, (case, text)
        temporary = Path(state["temporary"])
        if clean:
            assert state["resources"] == initial_canaries, (case, state)
            assert not temporary.exists(), case
            assert "residue=0." in text, (case, text)
        else:
            assert temporary.is_dir() and (temporary / "cleanup-resources.txt").is_file(), (case, text)
            assert "residue=0." not in text and "residue=1." in text, (case, text)
            plan = (temporary / "cleanup-resources.txt").read_text()
            assert state["project"] in plan and "-app" in plan, (case, plan)
            if mode in ("query", "query-partial", "query-malformed", "query-nonstring", "inspect", "inspect-partial",
                        "inspect-malformed", "foreign", "identity", "name", "extra-tag"):
                assert not any(k == kind for k, _ in state["removals"]), (case, state["removals"])
        child = int(Path(str(state_file) + ".child").read_text())
        try:
            os.kill(child, 0)
        except ProcessLookupError:
            pass
        else:
            raise AssertionError(case + " child remains alive")
        checks += 1
    except Exception:
        print("migration signal case: " + case, file=sys.stderr)
        print(output.read_text() if output.exists() else case, file=sys.stderr)
        raise
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()
        if process is not None and process.stdin:
            process.stdin.close()
        if state_file.exists():
            temporary = Path(json.loads(state_file.read_text())["temporary"])
            assert temporary.parent == root and temporary.name.startswith(".migration-test-tmp.")
            if temporary.exists():
                shutil.rmtree(temporary)

for kind in ("container", "network", "image"):
    for mode in ("none", "absent", "query", "query-partial", "query-malformed", "query-nonstring",
                 "inspect", "inspect-partial", "inspect-malformed", "foreign", "identity", "name",
                 "remove", "noop", "post-query", "replacement"):
        for incoming in (0, 7):
            run(kind, mode, incoming)
    for mode in ("none", "query-partial", "remove", "repeat"):
        for incoming in ("INT", "TERM"):
            run(kind, mode, incoming)
run("image", "extra-tag", 0)
run("image", "extra-tag", 7)
print(f"PostgreSQL migration cleanup: {checks} stateful Docker-stub scenarios passed; INT=130 TERM=143 nonzero=7; canaries preserved.")
PY
