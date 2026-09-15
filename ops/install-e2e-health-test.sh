#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t yunlume-e2e-health-test.XXXXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

# 仅移除入口分派，执行当前源码中的真实等待函数及错误处理。
python3 - "${PROJECT_DIR}/ops/install-e2e.sh" "${TEST_ROOT}/e2e-lib.sh" <<'PY'
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text(encoding="utf-8")
entry = '\ncase "${1:-plan}" in\n'
assert source.count(entry) == 1
Path(sys.argv[2]).write_text(source.split(entry)[0] + "\n", encoding="utf-8")
PY

cat >"${TEST_ROOT}/driver.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
source "$1/e2e-lib.sh"
scenario="$2"
context="$3"
expected="$4"
calls="$5"
printf '0\n' >"${calls}"
# 去掉 SECONDS 的墙钟属性，避免机器负载影响重试次数断言。
unset SECONDS
SECONDS=0
docker() {
  [[ "$*" == "container inspect --format {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}} fixture-container" ]] || return 97
  local count
  count="$(<"${calls}")"
  count=$((count + 1))
  printf '%s\n' "${count}" >"${calls}"
  case "${scenario}" in
    healthy) printf 'healthy\n' ;;
    running) printf 'running\n' ;;
    failed-healthy) printf 'healthy\n'; return 7 ;;
    failed-running) printf 'running\n'; return 7 ;;
    failed-empty) return 7 ;;
    empty) : ;;
    unknown|sleep-failure) printf 'unknown\n' ;;
    multiline) printf 'healthy\nrunning\n' ;;
    transient)
      printf 'healthy\n'
      [[ "${count}" -ge 2 ]]
      ;;
    *) return 98 ;;
  esac
}
sleep() {
  [[ "$1" == 2 ]] || return 96
  [[ "${scenario}" != sleep-failure ]] || return 9
  # 只加速时钟，不改变真实函数的超时或重试判定。
  SECONDS=$((SECONDS + 2))
}
if [[ "${context}" == conditional ]]; then
  if wait_container_health fixture-container "${expected}" 5; then
    printf 'HEALTH_CONFIRMED\n'
  else
    exit "$?"
  fi
else
  wait_container_health fixture-container "${expected}" 5
  printf 'HEALTH_CONFIRMED\n'
fi
SH

python3 - "${TEST_ROOT}" <<'PY'
import subprocess
import sys
from pathlib import Path
root = Path(sys.argv[1])
cases = [
    ("healthy", "healthy", 0, 1), ("running", "running", 0, 1),
    ("failed-healthy", "healthy", 1, 3), ("failed-running", "running", 1, 3),
    ("failed-empty", "healthy", 1, 3), ("empty", "healthy", 1, 3),
    ("unknown", "healthy", 1, 3), ("multiline", "healthy", 1, 3),
    ("transient", "healthy", 0, 2), ("sleep-failure", "healthy", 1, 1),
]
checks = 0
for context in ("bare", "conditional"):
    for scenario, expected, status, calls in cases:
        prefix = root / f"{context}-{scenario}"
        count_file = prefix.with_suffix(".calls")
        result = subprocess.run(["bash", str(root / "driver.sh"), str(root), scenario, context,
                                 expected, str(count_file)], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=10)
        prefix.with_suffix(".log").write_text(result.stdout, encoding="utf-8")
        assert result.returncode == status, (context, scenario, result.returncode, result.stdout)
        assert int(count_file.read_text()) == calls, (context, scenario, count_file.read_text())
        assert ("HEALTH_CONFIRMED" in result.stdout) == (status == 0), (scenario, result.stdout)
        checks += 1
print(f"E2E container health: {checks} real-function boundary cases passed")
PY
