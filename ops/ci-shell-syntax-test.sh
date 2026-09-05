#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
python3 - "${SCRIPT_DIR}/../.github/workflows/publish-images.yml" <<'PY'
from pathlib import Path
import subprocess
import sys
import tempfile

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
job = workflow.split("\n  installer-test:\n", 1)[1].split("\n  frontend-test:\n", 1)[0]
step = job.split("      - name: Verify installer scripts\n", 1)[1]
body = step.split("        run: |\n", 1)[1]
# 使用 CI 自身的完整语法门禁前缀，包括 set -e；不另外复制一套循环来证明自己。
prefix = body.split("          ops/install-release-url-test.sh\n", 1)[0]
script = "\n".join(line[10:] if line.startswith("          ") else line for line in prefix.splitlines()) + "\n"

valid = "#!/usr/bin/env bash\nprintf 'must not execute' > syntax-check-executed\n"
invalid = "#!/usr/bin/env bash\nif true; then\n"
names = ["install.sh", "ops/00-valid.sh", "ops/20-later.sh", "ops/30 with spaces.sh",
         "ops/lib/00-helper.sh", "nav-frontend/nginx/05-proxy.envsh"]
checks = 0

with tempfile.TemporaryDirectory(prefix="yunlume ci syntax ") as directory:
    root = Path(directory)
    for name in names:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(valid, encoding="utf-8")

    def run(label, expected_success, expected_path=None, command=script):
        global checks
        result = subprocess.run(["bash", "-c", command], cwd=root, text=True, capture_output=True, timeout=15)
        assert (result.returncode == 0) == expected_success, (
            f"{label}: exit={result.returncode}\nstdout={result.stdout}\nstderr={result.stderr}")
        if expected_path:
            assert expected_path in result.stderr, f"{label}: failure did not identify {expected_path}: {result.stderr}"
        assert not (root / "syntax-check-executed").exists(), f"{label}: script was executed instead of parsed"
        checks += 1

    run("all valid, including working directory and filename spaces", True)
    # install.sh 始终合法；每次只损坏一个后续文件，避免首文件错误掩盖漏检。
    for name in ("ops/20-later.sh", "ops/lib/00-helper.sh", "nav-frontend/nginx/05-proxy.envsh", "ops/30 with spaces.sh"):
        (root / name).write_text(invalid, encoding="utf-8")
        run("reject later syntax error: " + name, False, name)
        if name == "ops/20-later.sh":
            # 重现原多参数 bash -n 的漏检，保证同一坏文件确实能区分新旧门禁。
            run("legacy command misses the same later syntax error", True,
                command="bash -n install.sh ops/*.sh ops/lib/*.sh nav-frontend/nginx/*.envsh")
        (root / name).write_text(valid, encoding="utf-8")

    # 空文件是合法 shell；目录缺少该类脚本则保留 CI 的 fail-closed 行为。
    (root / "ops/20-later.sh").write_text("", encoding="utf-8")
    run("empty shell file is valid", True)
    (root / "nav-frontend/nginx/05-proxy.envsh").unlink()
    run("missing envsh family is rejected", False, "nav-frontend/nginx/*.envsh")

print(f"CI shell syntax behavior: {checks} scenarios passed using the real workflow command")
PY
