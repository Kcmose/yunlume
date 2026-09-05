#!/usr/bin/env bash
set -Eeuo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly SUITE="${ROOT_DIR}/ops/postgresql-migration-test.sh"
readonly TEST_DIR="$(mktemp -d "${ROOT_DIR}/.migration-signal-test.XXXXXXXX")"
trap 'rm -rf -- "${TEST_DIR}"' EXIT

# Exercise the suite's real setup and trap definitions, then stop before Docker setup.
sed -n '1,/^trap '\''handle_signal 143'\'' TERM$/p' "${SUITE}" >"${TEST_DIR}/harness.sh"
cat >>"${TEST_DIR}/harness.sh" <<'HARNESS'
printf 'ready\n'
while :; do :; done
HARNESS
chmod +x "${TEST_DIR}/harness.sh"
printf 'signal fixture\n' >"${TEST_DIR}/fixture.jar"
fixture_sha="$(sha256sum "${TEST_DIR}/fixture.jar" | cut -d' ' -f1)"

for signal_spec in TERM:143 INT:130; do
  signal="${signal_spec%%:*}"
  expected="${signal_spec##*:}"
  output="${TEST_DIR}/${signal}.out"
  python3 - "${TEST_DIR}/harness.sh" "${TEST_DIR}/fixture.jar" "${fixture_sha}" \
      "${signal}" "${expected}" "${output}" <<'PY'
import signal
import subprocess
import sys
import time

harness, fixture, digest, signal_name, expected, output = sys.argv[1:]
with open(output, "wb") as stream:
    process = subprocess.Popen([harness, fixture, digest], stdout=stream, stderr=subprocess.STDOUT)
    for _ in range(100):
        stream.flush()
        try:
            ready = b"ready\n" in open(output, "rb").read()
        except FileNotFoundError:
            ready = False
        if ready or process.poll() is not None:
            break
        time.sleep(0.05)
    if not ready:
        process.kill()
        raise SystemExit(f"ERROR: {signal_name} harness did not become ready")
    process.send_signal(getattr(signal, "SIG" + signal_name))
    returncode = process.wait(timeout=15)
status = 128 + (-returncode) if returncode < 0 else returncode
if status != int(expected):
    raise SystemExit(f"ERROR: {signal_name} harness exited {status}, expected {expected}")
PY
  grep -Eq 'children=0 residue=0\.$' "${output}" || {
    printf 'ERROR: %s cleanup did not prove zero residue\n' "${signal}" >&2
    cat "${output}" >&2
    exit 1
  }
  [[ "$(grep -Fc 'PostgreSQL migration cleanup:' "${output}")" -eq 1 ]] || {
    printf 'ERROR: %s cleanup ran more than once\n' "${signal}" >&2
    cat "${output}" >&2
    exit 1
  }
done

sed -n '1,/^trap '\''handle_signal 143'\'' TERM$/p' "${SUITE}" >"${TEST_DIR}/nonzero-harness.sh"
printf 'exit 7\n' >>"${TEST_DIR}/nonzero-harness.sh"
chmod +x "${TEST_DIR}/nonzero-harness.sh"
set +e
"${TEST_DIR}/nonzero-harness.sh" "${TEST_DIR}/fixture.jar" "${fixture_sha}" \
  >"${TEST_DIR}/nonzero.out" 2>&1
nonzero_status=$?
set -e
[[ "${nonzero_status}" -eq 7 ]] || {
  printf 'ERROR: cleanup changed incoming status 7 to %d\n' "${nonzero_status}" >&2
  cat "${TEST_DIR}/nonzero.out" >&2
  exit 1
}
grep -Eq 'children=0 residue=0\.$' "${TEST_DIR}/nonzero.out" || {
  printf 'ERROR: nonzero-exit cleanup did not prove zero residue\n' >&2
  exit 1
}

printf 'PostgreSQL migration signal cleanup tests passed: INT=130 TERM=143 nonzero=7 residue=0.\n'
