#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t yunlume-volume-presence-test.XXXXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

# 只移除 main 调用，保留目标存在性检查、创建后标签核对和真实错误处理。
python3 - "${PROJECT_DIR}/ops/migrate-docker-volumes.sh" "${TEST_ROOT}/migration-lib.sh" <<'PY'
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text(encoding="utf-8")
entry = '\nmain "$@"\n'
assert source.count(entry) == 1
Path(sys.argv[2]).write_text(source.replace(entry, "\n"), encoding="utf-8")
PY

cat >"${TEST_ROOT}/driver.sh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
source "$1/migration-lib.sh"
scenario="$2"
context="$3"
calls="$4"
RUN_ID=fixture-run
SOURCE_UPLOADS=source-volume
DESTINATION_UPLOADS=destination-volume
docker() {
  printf '%s\n' "$*" >>"${calls}"
  case "$*" in
    "volume ls --format {{.Name}}")
      case "${scenario}" in
        absent) : ;;
        unrelated) printf 'other-volume\nanother_volume\n' ;;
        similar) printf 'destination-volume-copy\nprefix-destination-volume\n' ;;
        exists) printf 'other-volume\ndestination-volume\nanother-volume\n' ;;
        failure) return 7 ;;
        partial-failure) printf 'other-volume\n'; return 7 ;;
        existing-failure) printf 'destination-volume\n'; return 7 ;;
        malformed) printf '{"Name":"other-volume"}\n' ;;
        blank-line) printf 'other-volume\n\nanother-volume\n' ;;
        whitespace) printf ' destination-volume \n' ;;
        *) return 98 ;;
      esac
      ;;
    "volume create --label com.yunlume.migration.run=fixture-run --label com.yunlume.migration.source=source-volume destination-volume")
      printf 'destination-volume\n'
      ;;
    "volume inspect --format {{ index .Labels \"com.yunlume.migration.run\" }} destination-volume")
      printf 'fixture-run\n'
      ;;
    *) return 97 ;;
  esac
}
invoke() {
  assert_destination_absent "${DESTINATION_UPLOADS}"
  create_destination "${DESTINATION_UPLOADS}" "${SOURCE_UPLOADS}"
  printf 'DESTINATION_CREATED\n'
}
if [[ "${context}" == conditional ]]; then
  if invoke; then exit 0; else exit "$?"; fi
else
  invoke
fi
SH

python3 - "${TEST_ROOT}" <<'PY'
import subprocess
import sys
from pathlib import Path
root = Path(sys.argv[1])
cases = [("absent", 0), ("unrelated", 0), ("similar", 0), ("exists", 1),
         ("failure", 1), ("partial-failure", 1), ("existing-failure", 1),
         ("malformed", 1), ("blank-line", 1), ("whitespace", 1)]
checks = 0
for context in ("bare", "conditional"):
    for scenario, status in cases:
        prefix = root / f"{context}-{scenario}"
        calls = prefix.with_suffix(".calls")
        result = subprocess.run(["bash", str(root / "driver.sh"), str(root), scenario, context,
                                 str(calls)], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=10)
        prefix.with_suffix(".log").write_text(result.stdout, encoding="utf-8")
        assert result.returncode == status, (context, scenario, result.returncode, result.stdout)
        trace = calls.read_text(encoding="utf-8").splitlines()
        assert trace[0] == "volume ls --format {{.Name}}", trace
        assert len(trace) == (3 if status == 0 else 1), (scenario, trace)
        assert any(line.startswith("volume create ") for line in trace) == (status == 0), (scenario, trace)
        assert ("DESTINATION_CREATED" in result.stdout) == (status == 0), (scenario, result.stdout)
        checks += 1
print(f"Migration destination presence: {checks} real-function boundary cases passed")
PY
