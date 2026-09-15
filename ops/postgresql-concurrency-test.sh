#!/usr/bin/env bash
# 调用者负责提供并销毁隔离 PostgreSQL 容器；本脚本只新建专用测试库。
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
container=${1:?usage: script CONTAINER_ID PUBLISHED_PORT POSTGRESQL_MAJOR}
port=${2:?published PostgreSQL port required}
major=${3:?PostgreSQL major required}
[[ "$container" =~ ^[0-9a-f]{64}$ ]] || { printf 'Expected exact container ID\n' >&2; exit 1; }
[[ "$port" =~ ^[0-9]{1,5}$ && "$((10#$port))" -ge 1 && "$((10#$port))" -le 65535 ]] || exit 1
[[ "$major" == 14 || "$major" == 18 ]] || exit 1
[[ "$(docker inspect --format '{{.Id}}' "$container")" == "$container" ]] || exit 1
mapped_ports=$(docker port "$container" 5432/tcp)
[[ "$mapped_ports" == *":$port" || "$mapped_ports" == *":$port"$'\n'* ]] || exit 1
version=$(docker exec "$container" psql -XAt -U postgres -d postgres -c 'SHOW server_version_num')
[[ "$version" =~ ^[0-9]+$ && "$((version / 10000))" -eq "$major" ]] || exit 1

cd "$SCRIPT_DIR/../nav-backend"
report_dir="target/concurrency-postgresql/pg${major}"
mkdir -p "$report_dir"
printf '%s\n' "$version" > "$report_dir/server-version.txt"
database="yunlume_concurrency_test_pg${major}"
role="yunlume_concurrency_pg${major}"
test_password=$(openssl rand -hex 24)
if [[ "${GITHUB_ACTIONS:-false}" == true ]]; then printf '::add-mask::%s\n' "$test_password"; fi
# 不复用或清空现有同名数据库/角色。重跑应由调用者提供新的隔离容器。
docker exec -i "$container" psql -X -U postgres -d postgres --set ON_ERROR_STOP=1 \
  --set role="$role" --set database="$database" --set password="$test_password" <<'SQL' > "$report_dir/setup.log"
CREATE ROLE :"role" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT PASSWORD :'password';
CREATE DATABASE :"database" OWNER :"role";
SQL
docker exec -i "$container" psql -X -U postgres -d "$database" --set ON_ERROR_STOP=1 \
  --set role="$role" <<'SQL' >> "$report_dir/setup.log"
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO :"role";
SQL
docker exec -i "$container" psql -X -U "$role" -d "$database" --set ON_ERROR_STOP=1 \
  < src/main/resources/schema-postgresql.sql >> "$report_dir/setup.log" 2>&1

classes=(com.example.nav.ConcurrentMutationIntegrationTest com.example.nav.SearchEnginePartialUpdateRegressionTest com.example.nav.NumericInputIntegrationTest)
for test_class in "${classes[@]}"; do
  rm -f -- "target/surefire-reports/TEST-${test_class}.xml" "$report_dir/TEST-${test_class}.xml"
done
maven=(mvn --batch-mode --no-transfer-progress)
if [[ "${CONCURRENT_MUTATION_MAVEN_OFFLINE:-false}" == true ]]; then maven+=(--offline); fi
set +e
CONCURRENT_MUTATION_TEST_URL="jdbc:postgresql://127.0.0.1:${port}/${database}" \
  CONCURRENT_MUTATION_TEST_USERNAME="$role" CONCURRENT_MUTATION_TEST_PASSWORD="$test_password" \
  "${maven[@]}" "-Dtest=${classes[0]},${classes[1]},${classes[2]}" test > "$report_dir/maven.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$report_dir/maven.exit"
tail -20 "$report_dir/maven.log"
for test_class in "${classes[@]}"; do
  xml="target/surefire-reports/TEST-${test_class}.xml"
  [[ -f "$xml" ]] || { printf 'Missing new report: %s\n' "$xml" >&2; exit 1; }
  cp "$xml" "$report_dir/"
done
[[ "$status" == 0 ]] || exit "$status"
# 每类均须实际运行；PG/H2 的选择另由测试中的数据库元数据断言验证。
python3 - "$report_dir" "${classes[@]}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = Path(sys.argv[1])
for test_class in sys.argv[2:]:
    suite = ET.parse(reports / f'TEST-{test_class}.xml').getroot()
    assert int(suite.attrib['tests']) > 0, f'{test_class} did not run'
    assert all(int(suite.attrib[name]) == 0 for name in ('failures', 'errors', 'skipped')), suite.attrib
    print(f'PostgreSQL concurrency {test_class}: {suite.attrib["tests"]} passed')
PY
