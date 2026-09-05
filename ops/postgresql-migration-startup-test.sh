#!/usr/bin/env bash
set -Eeuo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly TEST_DIR="$(mktemp -d "${ROOT_DIR}/.migration-startup-test.XXXXXXXX")"
trap 'rm -rf -- "${TEST_DIR}"' EXIT

# 提取真实启动/日志/登记函数；只控制外部 Docker、grep 故障和轮询时钟。
# 不运行镜像、访问 Docker socket 或把边界替身结果当作真实迁移验收。
python3 - "${ROOT_DIR}" "${TEST_DIR}" <<'PY'
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root, lab = map(Path, sys.argv[1:])
source = (root / "ops/postgresql-migration-test.sh").read_text()
start, end = "match_container_log() {\n", "assert_http_refused() {\n"
assert source.count(start) == source.count(end) == 1
helpers = start + source.split(start, 1)[1].split(end, 1)[0]
registration = "record_cleanup_plan() {\n" + source.split("record_cleanup_plan() {\n", 1)[1].split("cleanup() {\n", 1)[0]
definitions = "\n".join(line for line in source.splitlines() if line.startswith(("fail() {", "container_name() {")))
assert len(definitions.splitlines()) == 2
concurrent_start = 'concurrent_logs="$(docker logs'
assert source.count(concurrent_start) == 1
concurrent_read = concurrent_start + source.split(concurrent_start, 1)[1].split('[[ "$(grep -Fc', 1)[0]
bin_dir = lab / "bin"
bin_dir.mkdir()
docker = bin_dir / "docker"
docker.write_text(r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
path = Path(os.environ["STARTUP_TEST_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
action = args[0]
name = "startup-check-app"
if action == "inspect":
    assert args == ["inspect", "-f", "{{.State.Running}}", name], args
elif action == "logs":
    assert len(args) == 2 and args[1] in (name, "startup-check-concurrent-a", "startup-check-concurrent-b"), args
elif action == "run":
    assert args[args.index("--name") + 1] == name, args
    assert args[args.index("--network") + 1] == "startup-check-net", args
else:
    raise AssertionError("unexpected Docker command: " + repr(args))
index = sum(call[0] == action for call in state["calls"])
state["calls"].append(args)
path.write_text(json.dumps(state))
responses = state[action]
output, status = responses[min(index, len(responses) - 1)]
sys.stdout.write(output)
if status:
    print("injected Docker query/launch failure", file=sys.stderr)
raise SystemExit(status)
''')
docker.chmod(0o755)
sleep = bin_dir / "sleep"
sleep.write_text('#!/usr/bin/env bash\n[[ "$*" == 1 ]] || exit 97\n')
sleep.chmod(0o755)
real_grep = shutil.which("grep")
assert real_grep
grep = bin_dir / "grep"
grep.write_text('''#!/usr/bin/env bash
if [[ "$STARTUP_GREP_FAILURE" == 1 ]]; then
  printf 'Demo data bootstrap is disabled\n'
  printf 'injected grep read failure\n' >&2
  exit 7
fi
exec "$STARTUP_REAL_GREP" "$@"
''')
grep.chmod(0o755)

driver = lab / "driver.sh"
driver.write_text('''#!/usr/bin/env bash
set -u
if [[ "$OPTIONS" == on ]]; then set -eo pipefail; else set +e; set +o pipefail; fi
PROJECT=startup-check IMAGE=startup-check:fixture NETWORK=startup-check-net PG=startup-check-pg
TMP_DIR="$STARTUP_TEST_TMP"
containers=() common_env=()
invocations=0
''' + definitions + "\n" + registration + helpers + "\nread_concurrent_logs() {\n" + concurrent_read + "}\n" + r'''
invoke() {
  case "$TARGET" in
    state) container_is_running app ;;
    contains) log_contains app "$MARKER" ;;
    matches) log_matches app "$MARKER" ;;
    wait-failed) wait_failed app ;;
    wait-log) wait_for_log app "$MARKER" ;;
    failed) run_image_failed app testdb /fixture.jar ;;
    started) run_image_started app testdb test:image ;;
    concurrent) read_concurrent_logs ;;
    *) exit 97 ;;
  esac
}
case "$CONTEXT" in
  bare) invoke ;;
  conditional) if invoke; then exit 0; else exit "$?"; fi ;;
  negated) if ! invoke; then exit 1; else exit 0; fi ;;
  and) invoke && exit 0; exit "$?" ;;
  or) invoke || exit "$?" ;;
  substitution) output="$(invoke)"; exit "$?" ;;
  *) exit 97 ;;
esac
''')

marker = "Demo data bootstrap is disabled"
cases = []

def case(name, target, expected, *, inspect=None, logs=None, run=None, pattern=marker, grep_failure=False):
    cases.append(dict(name=name, target=target, expected=expected, inspect=inspect or [("false\n", 0)],
                      logs=logs or [("migration rejected\n", 0)], run=run or [("container-id\n", 0)],
                      pattern=pattern, grep_failure=grep_failure))

case("state-running", "state", 0, inspect=[("true\n", 0)])
case("state-stopped", "state", 1)
for output in ("", "false\n", "true\n"):
    case("state-cli-failure-" + repr(output), "state", 2, inspect=[(output, 7)])
for output in ("", "null\n", "false\ntrue\n"):
    case("state-invalid-" + repr(output), "state", 2, inspect=[(output, 0)])
for target in ("contains", "matches"):
    case(target + "-match", target, 0, logs=[(marker + "\n", 0)])
    case(target + "-absent", target, 1)
    case(target + "-empty", target, 1, logs=[("", 0)])
    case(target + "-read-failure", target, 2, logs=[("", 7)])
    case(target + "-partial-read-failure", target, 2, logs=[(marker + "\n", 7)])
    case(target + "-grep-read-failure", target, 2, grep_failure=True)
case("literal-leading-dash", "contains", 0, pattern="-marker", logs=[("-marker\n", 0)])
case("regex-alternatives", "matches", 0, pattern="rejected|corrupt")
case("regex-invalid", "matches", 2, pattern="[")
case("negative-confirmed-stopped", "wait-failed", 0)
case("negative-eventually-stopped", "wait-failed", 0, inspect=[("true\n", 0), ("false\n", 0)])
case("negative-business-started", "wait-failed", 1, inspect=[("true\n", 0)], logs=[(marker, 0)])
case("negative-inspect-unknown", "wait-failed", 2, inspect=[("false\n", 7)])
case("negative-logs-unknown", "wait-failed", 2, inspect=[("true\n", 0)], logs=[("", 7)])
case("negative-logs-partial", "wait-failed", 2, inspect=[("true\n", 0)], logs=[(marker, 7)])
case("positive-confirmed-running", "wait-log", 0, inspect=[("true\n", 0)], logs=[(marker, 0)])
case("positive-eventually-started", "wait-log", 0, inspect=[("true\n", 0)], logs=[("starting", 0), (marker, 0)])
case("positive-already-stopped", "wait-log", 1, logs=[(marker, 0)])
case("positive-inspect-unknown", "wait-log", 2, inspect=[("true\n", 7)], logs=[(marker, 0)])
case("positive-before-marker-inspect-unknown", "wait-log", 2, inspect=[("", 7)])
case("positive-logs-unknown", "wait-log", 2, inspect=[("true\n", 0)], logs=[(marker, 7)])
case("failed-container-accepted", "failed", 0)
case("failed-container-eventual-exit", "failed", 0, inspect=[("true\n", 0), ("false\n", 0)])
case("failed-container-still-running", "failed", 1, inspect=[("true\n", 0)], logs=[(marker, 0)])
case("failed-container-startup-marker", "failed", 1, logs=[(marker, 0)])
case("failed-container-inspect-error", "failed", 1, inspect=[("false\n", 7)])
case("failed-container-logs-error", "failed", 1, logs=[("", 7)])
case("failed-container-running-logs-error", "failed", 1, inspect=[("true\n", 0)], logs=[("", 7)])
case("failed-container-partial-logs-error", "failed", 1, logs=[(marker, 7)])
case("failed-container-run-error", "failed", 1, run=[("container-id\n", 7)])
case("started-container-accepted", "started", 0, inspect=[("true\n", 0)], logs=[(marker, 0)])
case("started-container-run-error", "started", 1, run=[("container-id\n", 7)])
case("started-container-query-error", "started", 1, inspect=[("true\n", 7)], logs=[(marker, 0)])
case("concurrent-logs-readable", "concurrent", 0, logs=[("first log", 0), ("second log", 0)])
case("concurrent-first-read-error", "concurrent", 1, logs=[("first partial log", 7), ("second log", 0)])
case("concurrent-second-read-error", "concurrent", 1, logs=[("first log", 0), ("second partial log", 7)])

checks = 0
state_file = lab / "state.json"
temporary = lab / "registered"
temporary.mkdir()

def check(spec, context, options):
    global checks
    state_file.write_text(json.dumps({key: spec[key] for key in ("inspect", "logs", "run")} | {"calls": []}))
    plan = temporary / "cleanup-resources.txt"
    if plan.exists():
        plan.unlink()
    env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
               STARTUP_TEST_STATE=str(state_file), STARTUP_TEST_TMP=str(temporary),
               STARTUP_REAL_GREP=real_grep, STARTUP_GREP_FAILURE=str(int(spec["grep_failure"])),
               OPTIONS=options, CONTEXT=context, TARGET=spec["target"], MARKER=spec["pattern"])
    result = subprocess.run(["bash", str(driver)], env=env, text=True, capture_output=True, timeout=20)
    expected = min(spec["expected"], 1) if context == "negated" else spec["expected"]
    label = f'{spec["name"]}/{context}/{options}'
    assert result.returncode == expected, (label, result.returncode, expected, result.stdout, result.stderr)
    calls = json.loads(state_file.read_text())["calls"]
    assert calls, label
    if spec["target"] in ("failed", "started"):
        assert calls[0][0] == "run" and plan.exists(), (label, calls)
        assert "startup-check-app" in plan.read_text(), label + " missing cleanup registration"
        if spec["run"][0][1]:
            assert len(calls) == 1, (label, "continued after launch failure", calls)
    if spec["target"] in ("wait-failed", "failed") and spec["inspect"][0][1]:
        assert not any(call[0] == "logs" for call in calls), (label, "continued after inspect failure", calls)
    if spec["target"] == "concurrent":
        expected_names = ["startup-check-concurrent-a"]
        if not spec["logs"][0][1]:
            expected_names.append("startup-check-concurrent-b")
        assert [call[-1] for call in calls] == expected_names, (label, calls)
    checks += 1

for options in ("on", "off"):
    for context in ("bare", "conditional", "negated", "and", "or", "substitution"):
        for spec in cases:
            check(spec, context, options)

# 保留真实 90 次轮询边界，时钟替身避免等待 90 秒。
case("negative-running-timeout", "wait-failed", 1, inspect=[("true\n", 0)])
check(cases[-1], "bare", "on")
calls = json.loads(state_file.read_text())["calls"]
assert sum(call[0] == "inspect" for call in calls) == 90
print(f"PostgreSQL migration startup: {checks} real-helper boundary scenarios passed; unknown state never accepted as expected failure.")
PY
