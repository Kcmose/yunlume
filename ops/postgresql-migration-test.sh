#!/usr/bin/env bash
set -Eeuo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
[[ $# -eq 2 ]] || { printf 'Usage: %s <exact-backend.jar> <sha256>\n' "${0##*/}" >&2; exit 2; }
readonly JAR="$(realpath -- "$1")"
readonly INPUT_JAR_SHA="$(sha256sum "${JAR}" | cut -d' ' -f1)"
readonly EXPECTED_JAR_SHA="$2"
[[ "${EXPECTED_JAR_SHA}" =~ ^[0-9a-f]{64}$ && "${INPUT_JAR_SHA}" == "${EXPECTED_JAR_SHA}" ]] || {
  printf 'ERROR: exact input JAR does not match supplied revision-bound SHA-256\n' >&2
  exit 1
}
readonly TMP_DIR="$(mktemp -d "${ROOT_DIR}/.migration-test-tmp.XXXXXXXX")"
readonly RUN_ID="${TMP_DIR##*.}-$PPID-$$"
readonly PROJECT="yunlume-migration-${RUN_ID,,}"
readonly IMAGE="yunlume-backend-migration-test:${RUN_ID,,}"
readonly PG="${PROJECT}-pg"
readonly NETWORK="${PROJECT}-net"
export COMPOSE_PROJECT_NAME="${PROJECT}"
readonly PASSWORD="runtime-test-password"
readonly MIGRATION="20260904_0004_portable_import_operations.sql"
readonly CHECKSUM="4de5e2df8c8f6780f6d1b25e16ee1dd99b7335c7b7475afb83c63f78cfa7ac63"
containers=()
child_pids=()
invocations=0
cleanup_started=0

cleanup() {
  local status=$? pid residue=0
  trap - EXIT INT TERM
  set +e
  if (( cleanup_started != 0 )); then
    exit "${status}"
  fi
  cleanup_started=1
  for pid in "${child_pids[@]:-}"; do
    [[ -n "${pid}" ]] || continue
    kill "${pid}" >/dev/null 2>&1 || true
  done
  for pid in "${child_pids[@]:-}"; do
    [[ -n "${pid}" ]] || continue
    wait "${pid}" >/dev/null 2>&1 || true
  done
  for container in "${containers[@]:-}"; do docker rm -f "${container}" >/dev/null 2>&1 || true; done
  project_containers="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" 2>/dev/null || true)"
  [[ -z "${project_containers}" ]] || docker rm -f ${project_containers} >/dev/null 2>&1 || true
  docker rm -f "${PG}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  docker image rm -f "${IMAGE}" >/dev/null 2>&1 || true
  rm -rf -- "${TMP_DIR}"
  [[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" 2>/dev/null || true)" ]] || residue=1
  ! docker network inspect "${NETWORK}" >/dev/null 2>&1 || residue=1
  ! docker image inspect "${IMAGE}" >/dev/null 2>&1 || residue=1
  [[ ! -e "${TMP_DIR}" ]] || residue=1
  for pid in "${child_pids[@]:-}"; do
    [[ -z "${pid}" ]] || ! kill -0 "${pid}" >/dev/null 2>&1 || residue=1
  done
  printf 'PostgreSQL migration cleanup: project=%s containers=0 network=absent image=absent temp=absent children=0 residue=%d.\n' \
    "${PROJECT}" "${residue}"
  (( residue == 0 || status != 0 )) || status=1
  exit "${status}"
}
handle_signal() {
  local status="$1"
  trap - INT TERM
  exit "${status}"
}
trap cleanup EXIT
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
container_name() { printf '%s-%s' "${PROJECT}" "$1"; }

[[ -s "${JAR}" ]] || fail "build the backend JAR before running this test"
cmp --silent \
  "${ROOT_DIR}/database/migrations/${MIGRATION}" \
  "${ROOT_DIR}/nav-backend/src/main/resources/database/migrations/${MIGRATION}" ||
  fail "canonical and classpath migrations differ"
[[ "$(sha256sum "${ROOT_DIR}/database/migrations/${MIGRATION}" | cut -d' ' -f1)" == "${CHECKSUM}" ]] ||
  fail "canonical migration checksum differs from the pinned checksum"
jar_entries="$(docker run --rm -v "${JAR}:/app.jar:ro" eclipse-temurin:17-jdk-jammy jar tf /app.jar)"
grep -Fxq "BOOT-INF/classes/database/migrations/${MIGRATION}" <<<"${jar_entries}" ||
  fail "migration is not packaged in the backend JAR"
grep -Fq 'ENTRYPOINT ["java", "-jar", "/app/app.jar"]' "${ROOT_DIR}/nav-backend/Dockerfile" ||
  fail "Docker runtime does not execute its packaged backend JAR"
grep -Fq -- '-jar __INSTALL_DIR__/current/backend/yunlume-backend.jar' \
  "${ROOT_DIR}/deploy/host/yunlume-backend.service" ||
  fail "Host runtime does not execute its packaged backend JAR"
! grep -Rq 'apply-postgresql-migrations.sh' "${ROOT_DIR}/install.sh" \
  "${ROOT_DIR}/deploy" "${ROOT_DIR}/docker-compose.yml" ||
  fail "an installer/runtime still advertises the removed operator migration path"
! grep -Eq 'staging_root.*/ops|temporary_release.*/ops|release_dir.*/ops' "${ROOT_DIR}/install.sh" ||
  fail "Host installer still requires the nonexistent ops package contract"
mkdir -p "${TMP_DIR}/frontend" "${TMP_DIR}/release"
printf '<!doctype html>\n' >"${TMP_DIR}/frontend/index.html"
BACKEND_JAR="${JAR}" FRONTEND_DIST="${TMP_DIR}/frontend" \
  OUTPUT_DIR="${TMP_DIR}/release" \
  "${ROOT_DIR}/ops/package-host-release.sh" 0.0.0 >/dev/null
host_archive="${TMP_DIR}/release/yunlume-host-v0.0.0.tar.gz"
host_jar_sha="$(tar -xOf "${host_archive}" backend/yunlume-backend.jar | sha256sum | cut -d' ' -f1)"
[[ "${host_jar_sha}" == "${INPUT_JAR_SHA}" ]] || fail "Host archive JAR differs from exact input JAR"
! tar -tzf "${host_archive}" | grep -Eq '(^|/)ops(/|$)' || fail "Host archive unexpectedly contains ops"
mkdir -p "${TMP_DIR}/host-staging" "${TMP_DIR}/fresh-release"
tar -xzf "${host_archive}" -C "${TMP_DIR}/host-staging"
for component in backend frontend database; do
  cp -a "${TMP_DIR}/host-staging/${component}" "${TMP_DIR}/fresh-release/${component}"
  diff --brief --recursive "${TMP_DIR}/host-staging/${component}" \
    "${TMP_DIR}/fresh-release/${component}" >/dev/null ||
    fail "fresh/existing Host release contract differs for ${component}"
done

mkdir -p "${TMP_DIR}/image"
cp -- "${JAR}" "${TMP_DIR}/image/app.jar"
printf '%s\n' 'FROM eclipse-temurin:17-jre-jammy' 'WORKDIR /app' 'COPY app.jar app.jar' \
  'ENTRYPOINT ["java", "-jar", "/app/app.jar"]' >"${TMP_DIR}/image/Dockerfile"
docker build -q --label "yunlume.migration-test.project=${PROJECT}" -t "${IMAGE}" "${TMP_DIR}/image" >/dev/null
image_jar_sha="$(docker run --rm --entrypoint sha256sum "${IMAGE}" /app/app.jar | cut -d' ' -f1)"
[[ "${image_jar_sha}" == "${INPUT_JAR_SHA}" ]] || fail "Docker image JAR differs from exact input JAR"
docker network create --label "com.docker.compose.project=${PROJECT}" "${NETWORK}" >/dev/null
docker run -d --rm --name "${PG}" --network "${NETWORK}" \
  --label "com.docker.compose.project=${PROJECT}" \
  -e POSTGRES_PASSWORD="${PASSWORD}" postgres:17-bookworm >/dev/null
for _ in $(seq 1 60); do
  docker exec -e PGPASSWORD="${PASSWORD}" "${PG}" pg_isready -U postgres -d postgres >/dev/null 2>&1 && break
  sleep 1
done
docker exec -e PGPASSWORD="${PASSWORD}" "${PG}" pg_isready -U postgres -d postgres >/dev/null

psql_exec() {
  local database="$1"; shift
  docker exec -e PGPASSWORD="${PASSWORD}" "${PG}" psql -v ON_ERROR_STOP=1 -U postgres -d "${database}" "$@"
}
advisory_lock_observation() {
  local database="$1" granted="$2" applications="$3" lock_function="$4"
  psql_exec "${database}" -Atc \
    "SELECT coalesce(string_agg(observation, ',' ORDER BY observation), '')
       FROM (SELECT a.application_name || ':' || count(DISTINCT a.pid) || ':' || count(*) AS observation
       FROM pg_catalog.pg_stat_activity a
       JOIN pg_catalog.pg_locks l ON l.pid = a.pid
      WHERE a.datname = current_database()
        AND a.application_name IN (${applications})
        AND a.backend_type = 'client backend'
        AND position('${lock_function}' in a.query) > 0
        AND l.locktype = 'advisory'
        AND l.database = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
        AND l.classid::bigint = 1482249030
        AND l.objid::bigint = 1884829505
        AND l.objsubid = 1
        AND l.mode = 'ExclusiveLock'
        AND l.fastpath IS FALSE
        AND l.granted IS ${granted}
       GROUP BY a.application_name) observed;"
}
wait_for_advisory_lock_observation() {
  local database="$1" granted="$2" applications="$3" lock_function="$4" expected="$5" description="$6"
  for _ in $(seq 1 120); do
    [[ "$(advisory_lock_observation "${database}" "${granted}" "${applications}" "${lock_function}")" == "${expected}" ]] && return 0
    sleep 1
  done
  printf 'ERROR: timed out waiting for %s\n' "${description}" >&2
  psql_exec "${database}" -x -c \
    "SELECT a.pid, a.application_name, a.state, a.wait_event_type, a.wait_event,
            l.granted, l.classid, l.objid, l.objsubid
       FROM pg_catalog.pg_stat_activity a
       LEFT JOIN pg_catalog.pg_locks l ON l.pid = a.pid AND l.locktype = 'advisory'
      WHERE a.datname = current_database()
      ORDER BY a.pid, l.granted;" >&2 || true
  return 1
}
three_session_lock_snapshot() {
  local database="$1" holder_pid="${2:-}" waiter_a_pid="${3:-}" waiter_b_pid="${4:-}"
  psql_exec "${database}" -Atc \
    "WITH expected(role, application_name, should_be_granted, expected_pid) AS (
       VALUES
         ('holder', 'migration-test-holder-concurrent-v3', true, NULLIF('${holder_pid}', '')::integer),
         ('waiter_a', 'yunlume-migration-concurrent-a', false, NULLIF('${waiter_a_pid}', '')::integer),
         ('waiter_b', 'yunlume-migration-concurrent-b', false, NULLIF('${waiter_b_pid}', '')::integer)
     ), observed AS MATERIALIZED (
       SELECT e.role, e.should_be_granted, e.expected_pid, a.pid, a.application_name,
              a.backend_type, a.datname, a.state, a.wait_event_type, a.wait_event, a.query,
              l.locktype, l.database, l.classid, l.objid,
              l.objsubid, l.mode, l.fastpath, l.granted
         FROM expected e
         JOIN pg_catalog.pg_stat_activity a ON a.application_name = e.application_name
         JOIN pg_catalog.pg_locks l ON l.pid = a.pid AND l.locktype = 'advisory'
        WHERE a.datname = current_database()
     )
     SELECT max(pid) FILTER (WHERE role = 'holder') || '|' ||
            max(pid) FILTER (WHERE role = 'waiter_a') || '|' ||
            max(pid) FILTER (WHERE role = 'waiter_b')
       FROM observed
      HAVING count(*) = 3
         AND count(DISTINCT pid) = 3
         AND count(*) FILTER (WHERE role = 'holder') = 1
         AND count(*) FILTER (WHERE role = 'waiter_a') = 1
         AND count(*) FILTER (WHERE role = 'waiter_b') = 1
         AND bool_and(expected_pid IS NULL OR pid = expected_pid)
         AND bool_and(backend_type = 'client backend')
         AND bool_and(datname = current_database())
         AND bool_and((state = 'active') IS TRUE)
         AND bool_and((CASE role
               WHEN 'holder' THEN wait_event_type = 'Timeout' AND wait_event = 'PgSleep'
                                  AND position('pg_sleep' in query) > 0
               ELSE wait_event_type = 'Lock' AND wait_event = 'advisory'
                    AND position('pg_advisory_xact_lock' in query) > 0
             END) IS TRUE)
         AND bool_and(locktype = 'advisory')
         AND bool_and(database = (SELECT oid FROM pg_catalog.pg_database
                                   WHERE datname = current_database()))
         AND bool_and(classid::bigint = 1482249030)
         AND bool_and(objid::bigint = 1884829505)
         AND bool_and(objsubid = 1)
         AND bool_and(mode = 'ExclusiveLock')
         AND bool_and(fastpath IS FALSE)
         AND bool_and(granted = should_be_granted);"
}
wait_for_three_session_lock_snapshot() {
  local database="$1" snapshot
  for _ in $(seq 1 120); do
    snapshot="$(three_session_lock_snapshot "${database}")"
    if [[ "${snapshot}" =~ ^[0-9]+\|[0-9]+\|[0-9]+$ ]]; then
      IFS='|' read -r EXTERNAL_LOCK_BACKEND_PID CONCURRENT_A_BACKEND_PID CONCURRENT_B_BACKEND_PID <<<"${snapshot}"
      THREE_SESSION_LOCK_SNAPSHOT="${snapshot}"
      return 0
    fi
    sleep 1
  done
  printf 'ERROR: timed out waiting for atomic holder/two-waiter advisory-lock identity snapshot\n' >&2
  return 1
}
start_external_lock_holder() {
  local database="$1" application_name="$2"
  docker exec -e PGPASSWORD="${PASSWORD}" -e PGAPPNAME="${application_name}" "${PG}" \
    psql -v ON_ERROR_STOP=1 -U postgres -d "${database}" \
    -c "SELECT pg_advisory_lock(6366211110262552385); SELECT pg_sleep(120);" >/dev/null &
  EXTERNAL_LOCK_PROCESS_PID=$!
  child_pids+=("${EXTERNAL_LOCK_PROCESS_PID}")
  wait_for_advisory_lock_observation "${database}" TRUE "'${application_name}'" pg_sleep \
    "${application_name}:1:1" \
    "external advisory-lock holder ${application_name}" || return 1
  EXTERNAL_LOCK_BACKEND_PID="$(psql_exec "${database}" -Atc \
    "SELECT a.pid FROM pg_catalog.pg_stat_activity a
       JOIN pg_catalog.pg_locks l ON l.pid=a.pid
      WHERE a.datname=current_database() AND a.application_name='${application_name}'
        AND l.locktype='advisory' AND l.classid::bigint=1482249030
        AND l.objid::bigint=1884829505 AND l.objsubid=1 AND l.mode='ExclusiveLock'
        AND l.granted AND position('pg_sleep' in a.query)>0")"
  [[ "${EXTERNAL_LOCK_BACKEND_PID}" =~ ^[0-9]+$ ]] || fail "could not identify external advisory-lock holder"
}
release_external_lock_holder() {
  local database="$1"
  [[ "$(psql_exec "${database}" -Atc "SELECT pg_terminate_backend(${EXTERNAL_LOCK_BACKEND_PID})")" == t ]] ||
    fail "could not release external advisory-lock holder"
  wait "${EXTERNAL_LOCK_PROCESS_PID}" 2>/dev/null || true
  for index in "${!child_pids[@]}"; do
    [[ "${child_pids[${index}]}" != "${EXTERNAL_LOCK_PROCESS_PID}" ]] || child_pids[${index}]=''
  done
}
pg_version_num="$(psql_exec postgres -Atc 'SHOW server_version_num')"
(( pg_version_num >= 170000 && pg_version_num < 180000 )) || fail "runtime database is not PostgreSQL 17"
prepare_v3() {
  local database="$1"
  psql_exec postgres -c "CREATE DATABASE ${database}" >/dev/null
  psql_exec "${database}" -f /work/schema.sql >/dev/null
  psql_exec "${database}" -c "DROP TABLE portable_import_operation; DROP TABLE portable_import_guard; ALTER TABLE site_config DROP CONSTRAINT chk_site_config_version_range; DELETE FROM schema_migration WHERE filename='${MIGRATION}';" >/dev/null
}
prepare_registered_v4() {
  local database="$1"
  prepare_v3 "${database}"
  psql_exec "${database}" -f /work/migration.sql >/dev/null
  psql_exec "${database}" -c \
    "INSERT INTO schema_migration(filename, checksum) VALUES ('${MIGRATION}', '${CHECKSUM}');" >/dev/null
}
prepare_seeded_v3() {
  local database="$1" seed_sql
  prepare_v3 "${database}"
  read -r -d '' seed_sql <<'SQL' || true
TRUNCATE custom_link, search_engine, nav_bookmark, nav_category, site_config, sys_user RESTART IDENTITY CASCADE;
INSERT INTO sys_user (id, username, password, nickname, avatar, role, status, token_version, created_at, updated_at)
VALUES (101, 'v3-user', '$2a$10$preserve-byte-string', E'舊用戶\t☃', '/v3/avatar.png', 'admin', true, 7,
        TIMESTAMP '2025-01-02 03:04:05.123456', TIMESTAMP '2025-06-07 08:09:10.654321');
INSERT INTO site_config (id, site_name, site_description, publish_url, background_type, background_color,
                         background_image, mobile_background_image, font_color, background_effect,
                         music_enabled, music_url, subscribe_enabled, top_content_enabled, message_text,
                         version, install_completed_at, install_instance_id, created_at, updated_at)
VALUES (101, 'v3-site-網站', E'line 1\nline 2', 'https://v3.example.test/', 'image', '#010203',
        '/v3/background.png', '/v3/mobile.png', '#fefefe', true, true, '/v3/music.mp3', true, false,
        '保留訊息', 37, TIMESTAMP '2025-02-03 04:05:06', '12345678-1234-5678-9abc-123456789abc',
        TIMESTAMP '2025-02-03 04:05:06.111111', TIMESTAMP '2025-07-08 09:10:11.222222');
INSERT INTO nav_category (id, name, icon, sort_order, visible, created_at, updated_at)
VALUES (101, 'v3-category-分類', '⌘', -17, false,
        TIMESTAMP '2025-03-04 05:06:07.333333', TIMESTAMP '2025-08-09 10:11:12.444444');
INSERT INTO nav_bookmark (id, category_id, name, url, icon, description, sort_order, is_recommend,
                          is_external, visible, created_at, updated_at)
VALUES (101, 101, 'v3-bookmark-書籤', 'https://v3.example.test/a?x=1&y=%E9%9B%AA', '/v3/icon.svg',
        E'quote '' slash \\ newline\n雪', 23, true, false, false,
        TIMESTAMP '2025-04-05 06:07:08.555555', TIMESTAMP '2025-09-10 11:12:13.666666');
INSERT INTO search_engine (id, name, icon, search_url, placeholder, is_default, sort_order, visible,
                           created_at, updated_at)
VALUES (101, 'v3-search-搜尋', '/v3/search.svg', 'https://v3.example.test/search?q={keyword}', '查詢…',
        true, 31, true, TIMESTAMP '2025-05-06 07:08:09.777777', TIMESTAMP '2025-10-11 12:13:14.888888');
INSERT INTO custom_link (id, title, url, position, sort_order, visible, created_at, updated_at)
VALUES (101, 'v3-link-連結', '/v3/path#fragment', 'footer', 41, false,
        TIMESTAMP '2025-06-07 08:09:10.999999', TIMESTAMP '2025-11-12 13:14:15.000001');
SQL
  psql_exec "${database}" -c "${seed_sql}" >/dev/null
}
v3_data_fingerprint() {
  local database="$1" table
  for table in sys_user site_config nav_category nav_bookmark search_engine custom_link; do
    printf '%s:' "${table}"
    psql_exec "${database}" -c \
      "COPY (SELECT * FROM public.${table} ORDER BY id) TO STDOUT WITH (FORMAT binary)" | sha256sum | cut -d' ' -f1
  done
}
v3_row_counts() {
  local database="$1"
  psql_exec "${database}" -Atc \
    "SELECT (SELECT count(*) FROM sys_user)||':'||(SELECT count(*) FROM site_config)||':'||(SELECT count(*) FROM nav_category)||':'||(SELECT count(*) FROM nav_bookmark)||':'||(SELECT count(*) FROM search_engine)||':'||(SELECT count(*) FROM custom_link)"
}

# PostgreSQL reads the schema fixture; application containers receive no mounted
# migration runner and use only their configured datasource credentials.
docker cp "${ROOT_DIR}/nav-backend/src/main/resources/schema-postgresql.sql" "${PG}:/work-schema.sql"
docker cp "${ROOT_DIR}/database/migrations/${MIGRATION}" "${PG}:/work-migration.sql"
docker exec "${PG}" mkdir -p /work
docker exec "${PG}" mv /work-schema.sql /work/schema.sql
docker exec "${PG}" mv /work-migration.sql /work/migration.sql

common_env=(
  -e SPRING_PROFILES_ACTIVE=prod
  -e DB_USERNAME=postgres
  -e DB_PASSWORD="${PASSWORD}"
  -e NAV_DATABASE_SOURCE=LEGACY_ENV
  -e NAV_REDIS_SOURCE=UNCONFIGURED
  -e CACHE_TYPE=redis
  -e NAV_BOOTSTRAP_ENABLED=true
  -e NAV_DEMO_DATA_ENABLED=false
  -e ADMIN_USERNAME=admin
  -e ADMIN_PASSWORD='Migration-X7!Quartz-2026-Strong'
  -e JWT_SECRET='migration-test-jwt-secret-with-more-than-thirty-two-bytes'
  -e SERVER_PORT=0
)

log_contains() {
  local output container
  container="$(container_name "$1")"
  output="$(docker logs "${container}" 2>&1)"
  grep -Fq "$2" <<<"${output}"
}
log_matches() {
  local output container
  container="$(container_name "$1")"
  output="$(docker logs "${container}" 2>&1)"
  grep -Eq "$2" <<<"${output}"
}

wait_for_log() {
  local logical="$1" marker="$2" container
  container="$(container_name "${logical}")"
  for _ in $(seq 1 90); do
    if log_contains "${logical}" "${marker}"; then
      [[ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null || true)" == true ]]
      return
    fi
    [[ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null || true)" == true ]] || break
    sleep 1
  done
  docker logs "${container}" >&2 || true
  return 1
}
wait_failed() {
  local logical="$1" container
  container="$(container_name "${logical}")"
  for _ in $(seq 1 90); do
    [[ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null || true)" != true ]] && return 0
    log_contains "${logical}" 'Demo data bootstrap is disabled' && return 1
    sleep 1
  done
  docker logs "${container}" >&2 || true
  return 1
}
run_image_started() {
  local name="$1" database="$2" image="$3" jar_mount="${4:-}" container
  local mounts=() command=()
  container="$(container_name "${name}")"
  if [[ -n "${jar_mount}" ]]; then
    mounts=(-v "${jar_mount}:/app/app.jar:ro")
    command=(java -jar /app/app.jar)
  fi
  containers+=("${container}"); ((++invocations))
  docker run -d --name "${container}" --network "${NETWORK}" \
    --label "com.docker.compose.project=${PROJECT}" "${mounts[@]}" \
    "${common_env[@]}" -e DB_URL="jdbc:postgresql://${PG}:5432/${database}" \
    "${image}" "${command[@]}" >/dev/null
  wait_for_log "${name}" 'Demo data bootstrap is disabled' || fail "${name} did not reach business startup"
}
run_image_failed() {
  local name="$1" database="$2" jar="$3" container
  container="$(container_name "${name}")"
  containers+=("${container}"); ((++invocations))
  docker run -d --name "${container}" --network "${NETWORK}" \
    --label "com.docker.compose.project=${PROJECT}" -v "${jar}:/app/app.jar:ro" \
    "${common_env[@]}" -e DB_URL="jdbc:postgresql://${PG}:5432/${database}" \
    eclipse-temurin:17-jre-jammy java -jar /app/app.jar >/dev/null
  wait_failed "${name}" || fail "${name} unexpectedly remained running"
  ! log_contains "${name}" 'Demo data bootstrap is disabled' ||
    fail "${name} reached business initialization"
}
assert_http_refused() {
  local port="$1"
  ! curl --silent --show-error --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${port}/api/health" >/dev/null 2>&1 ||
    fail "HTTP request was accepted before migration completion"
}
assert_applied() {
  local database="$1"
  [[ "$(psql_exec "${database}" -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}' AND checksum='${CHECKSUM}'")" == 1 ]]
  [[ "$(psql_exec "${database}" -Atc "SELECT count(*) || ':' || min(id) FROM portable_import_guard")" == '1:1' ]]
  [[ "$(psql_exec "${database}" -Atc "SELECT count(*) FROM pg_constraint WHERE conrelid='site_config'::regclass AND conname='chk_site_config_version_range' AND contype='c' AND convalidated")" == 1 ]]
  [[ "$(psql_exec "${database}" -Atc "SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='site_config' AND column_name='version'")" == NO ]]
}

prepare_v3 startup_gate
start_external_lock_holder startup_gate migration-test-holder-startup-gate
startup_gate_container="$(container_name startup-gate)"
containers+=("${startup_gate_container}"); ((++invocations))
docker run -d --name "${startup_gate_container}" --network "${NETWORK}" \
  --label "com.docker.compose.project=${PROJECT}" -p 127.0.0.1::18080 \
  -v "${JAR}:/app/app.jar:ro" "${common_env[@]}" -e SERVER_PORT=18080 \
  -e DB_URL="jdbc:postgresql://${PG}:5432/startup_gate" \
  eclipse-temurin:17-jre-jammy java -jar /app/app.jar >/dev/null
gate_port="$(docker port "${startup_gate_container}" 18080/tcp | sed 's/.*://')"
for _ in $(seq 1 4); do assert_http_refused "${gate_port}"; sleep 1; done
release_external_lock_holder startup_gate
wait_for_log startup-gate "PostgreSQL migration ${MIGRATION} applied" || fail "gated migration did not complete"
docker rm -f "${startup_gate_container}" >/dev/null

prepare_seeded_v3 concurrent_v3
preservation_before="$(v3_data_fingerprint concurrent_v3)"
counts_before="$(v3_row_counts concurrent_v3)"
[[ "${counts_before}" == '1:1:1:1:1:1' ]] || fail "representative v3 seed row counts are wrong"
start_external_lock_holder concurrent_v3 migration-test-holder-concurrent-v3
for suffix in a b; do
  name="concurrent-${suffix}"
  container="$(container_name "${name}")"
  containers+=("${container}"); ((++invocations))
  docker run -d --name "${container}" --network "${NETWORK}" \
    --label "com.docker.compose.project=${PROJECT}" \
    -v "${JAR}:/app/app.jar:ro" "${common_env[@]}" \
    -e DB_URL="jdbc:postgresql://${PG}:5432/concurrent_v3?ApplicationName=yunlume-migration-concurrent-${suffix}" \
    eclipse-temurin:17-jre-jammy java -jar /app/app.jar >/dev/null
done
wait_for_three_session_lock_snapshot concurrent_v3 || {
    docker logs "$(container_name concurrent-a)" >&2 || true
    docker logs "$(container_name concurrent-b)" >&2 || true
    fail 'holder and concurrent applications did not overlap in one exact advisory-lock snapshot'
  }
[[ "$(psql_exec concurrent_v3 -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}'")" == 0 ]] ||
  fail "a concurrent application bypassed the PostgreSQL advisory lock"
for suffix in a b; do
  [[ "$(docker inspect -f '{{.State.Running}}' "$(container_name "concurrent-${suffix}")")" == true ]] ||
    fail "concurrent-${suffix} exited while waiting for the migration lock"
done
revalidated_lock_snapshot="$(three_session_lock_snapshot concurrent_v3 \
  "${EXTERNAL_LOCK_BACKEND_PID}" "${CONCURRENT_A_BACKEND_PID}" "${CONCURRENT_B_BACKEND_PID}")"
[[ "${revalidated_lock_snapshot}" == "${THREE_SESSION_LOCK_SNAPSHOT}" ]] ||
  fail 'holder/waiter advisory-lock identities changed immediately before release'
release_external_lock_holder concurrent_v3
wait_for_log concurrent-a 'Demo data bootstrap is disabled' || fail 'first concurrent startup did not converge'
wait_for_log concurrent-b 'Demo data bootstrap is disabled' || fail 'second concurrent startup did not converge'
assert_applied concurrent_v3
[[ "$(psql_exec concurrent_v3 -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}'")" == 1 ]] ||
  fail "concurrent startup did not produce exactly one migration registration"
concurrent_logs="$(docker logs "$(container_name concurrent-a)" 2>&1; docker logs "$(container_name concurrent-b)" 2>&1)"
[[ "$(grep -Fc "PostgreSQL migration ${MIGRATION} applied" <<<"${concurrent_logs}")" == 1 ]] ||
  fail "concurrent startup did not apply the migration exactly once"
[[ "$(grep -Fc "PostgreSQL migration ${MIGRATION} already applied" <<<"${concurrent_logs}")" == 1 ]] ||
  fail "concurrent startup did not converge through the registered migration"
preservation_after="$(v3_data_fingerprint concurrent_v3)"
counts_after="$(v3_row_counts concurrent_v3)"
[[ "${preservation_after}" == "${preservation_before}" ]] || fail "existing v3 row bytes changed during upgrade"
[[ "${counts_after}" == "${counts_before}" ]] || fail "existing v3 row counts changed during upgrade"
docker rm -f "$(container_name concurrent-a)" "$(container_name concurrent-b)" >/dev/null

prepare_v3 docker_v3
run_image_started docker-runtime docker_v3 "${IMAGE}"
assert_applied docker_v3
if psql_exec docker_v3 -c "UPDATE site_config SET version=-1 WHERE id=1" >/dev/null 2>&1; then
  fail "PostgreSQL accepted a negative site_config version update"
fi
[[ "$(psql_exec docker_v3 -Atc 'SELECT version FROM site_config WHERE id=1')" == 0 ]] ||
  fail "failed negative update mutated site_config.version"
if psql_exec docker_v3 -c "INSERT INTO site_config(id, site_name, version) VALUES (9001, 'negative', -1)" >/dev/null 2>&1; then
  fail "PostgreSQL accepted a negative site_config version insert"
fi
[[ "$(psql_exec docker_v3 -Atc 'SELECT count(*) FROM site_config WHERE id=9001')" == 0 ]] ||
  fail "failed negative insert created a site_config row"
docker_logs="$(docker logs "$(container_name docker-runtime)" 2>&1)"
[[ "${docker_logs}" == *"PostgreSQL migration ${MIGRATION} applied"* ]]
[[ "${docker_logs}" == *"Demo data bootstrap is disabled"* ]]
[[ "${docker_logs%%Demo data bootstrap is disabled*}" == *"PostgreSQL migration ${MIGRATION} applied"* ]] ||
  fail "Docker business initialization preceded migration"
docker rm -f "$(container_name docker-runtime)" >/dev/null

prepare_v3 host_v3
run_image_started host-runtime host_v3 eclipse-temurin:17-jre-jammy "${JAR}"
assert_applied host_v3
docker rm -f "$(container_name host-runtime)" >/dev/null
run_image_started host-repeat host_v3 eclipse-temurin:17-jre-jammy "${JAR}"
[[ "$(psql_exec host_v3 -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}'")" == 1 ]]
log_contains host-repeat "PostgreSQL migration ${MIGRATION} already applied"
docker rm -f "$(container_name host-repeat)" >/dev/null

prepare_v3 ddl_failure
psql_exec ddl_failure -c 'CREATE TABLE portable_import_operation (broken integer);' >/dev/null
run_image_failed ddl-failure ddl_failure "${JAR}"
[[ "$(psql_exec ddl_failure -Atc "SELECT to_regclass('public.portable_import_guard') IS NULL")" == t ]]
[[ "$(psql_exec ddl_failure -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}'")" == 0 ]]

prepare_v3 negative_generation
psql_exec negative_generation -c 'UPDATE site_config SET version=-1 WHERE id=1;' >/dev/null
run_image_failed negative-generation negative_generation "${JAR}"
[[ "$(psql_exec negative_generation -Atc 'SELECT version FROM site_config WHERE id=1')" == -1 ]]
[[ "$(psql_exec negative_generation -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}'")" == 0 ]]

prepare_v3 checksum_mismatch
psql_exec checksum_mismatch -c "INSERT INTO schema_migration(filename, checksum) VALUES ('${MIGRATION}', repeat('0',64));" >/dev/null
run_image_failed registration-mismatch checksum_mismatch "${JAR}"
log_contains registration-mismatch 'migration checksum registration mismatch'
[[ "$(psql_exec checksum_mismatch -Atc "SELECT to_regclass('public.portable_import_guard') IS NULL")" == t ]]

prepare_v3 corrupt_registration
psql_exec corrupt_registration -c "INSERT INTO schema_migration(filename, checksum) VALUES ('${MIGRATION}', '${CHECKSUM}');" >/dev/null
run_image_failed corrupt-registration corrupt_registration "${JAR}"
log_matches corrupt-registration 'portable_import_guard|partial or corrupt'

prepare_registered_v4 corrupt_index
psql_exec corrupt_index -c 'DROP INDEX idx_portable_import_user_committed;' >/dev/null
run_image_failed corrupt-registered-index corrupt_index "${JAR}"
log_contains corrupt-registered-index 'registered migration schema is partial or corrupt'

prepare_registered_v4 corrupt_constraint
psql_exec corrupt_constraint -c \
  'ALTER TABLE portable_import_operation DROP CONSTRAINT uk_portable_import_preview;' >/dev/null
run_image_failed corrupt-registered-constraint corrupt_constraint "${JAR}"
log_contains corrupt-registered-constraint 'registered migration schema is partial or corrupt'

prepare_registered_v4 corrupt_shape
psql_exec corrupt_shape -c 'ALTER TABLE portable_import_operation ALTER COLUMN job_id TYPE varchar(63);' >/dev/null
run_image_failed corrupt-registered-shape corrupt_shape "${JAR}"
log_contains corrupt-registered-shape 'registered migration schema is partial or corrupt'

registry_variant() {
  local name="$1" sql="$2"
  printf 'Checking fail-closed registry variant: %s\n' "${name}"
  prepare_v3 "${name}"
  psql_exec "${name}" -c "${sql}" >/dev/null
  run_image_failed "${name}" "${name}" "${JAR}"
  log_contains "${name}" 'schema_migration is partial or corrupt' || {
    docker logs "$(container_name "${name}")" >&2 || true
    fail "${name} did not fail through the schema_migration integrity guard"
  }
}
registry_variant registry_generated_filename "DROP TABLE schema_migration; CREATE TABLE schema_migration (filename varchar(255) GENERATED ALWAYS AS ('generated.sql'::varchar(255)) STORED NOT NULL, checksum char(64) NOT NULL, applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT schema_migration_pkey PRIMARY KEY (filename), CONSTRAINT chk_schema_migration_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'));"
registry_variant registry_extra_index 'CREATE INDEX extra_schema_migration_checksum ON schema_migration(checksum);'
registry_variant registry_extra_column 'ALTER TABLE schema_migration ADD COLUMN extra integer;'
registry_variant registry_wrong_filename 'ALTER TABLE schema_migration ALTER COLUMN filename TYPE varchar(254);'
registry_variant registry_filename_noncanonical_collation 'ALTER TABLE schema_migration ALTER COLUMN filename TYPE varchar(255) COLLATE "C";'
registry_variant registry_checksum_noncanonical_collation 'ALTER TABLE schema_migration ALTER COLUMN checksum TYPE char(64) COLLATE "C";'
registry_variant registry_wrong_checksum_type 'ALTER TABLE schema_migration DROP CONSTRAINT chk_schema_migration_checksum; ALTER TABLE schema_migration ALTER COLUMN checksum TYPE varchar(64); ALTER TABLE schema_migration ADD CONSTRAINT chk_schema_migration_checksum CHECK (checksum ~ '\''^[0-9a-f]{64}$'\'');'
registry_variant registry_wrong_checksum "ALTER TABLE schema_migration DROP CONSTRAINT chk_schema_migration_checksum; ALTER TABLE schema_migration ADD CONSTRAINT chk_schema_migration_checksum CHECK (length(checksum)=64);"
registry_variant registry_nullable 'ALTER TABLE schema_migration ALTER COLUMN checksum DROP NOT NULL;'
registry_variant registry_filename_nullable 'ALTER TABLE schema_migration DROP CONSTRAINT schema_migration_pkey; ALTER TABLE schema_migration ALTER COLUMN filename DROP NOT NULL;'
registry_variant registry_filename_default "ALTER TABLE schema_migration ALTER COLUMN filename SET DEFAULT 'unexpected.sql';"
registry_variant registry_wrong_default "ALTER TABLE schema_migration ALTER COLUMN applied_at SET DEFAULT now() + interval '1 second';"
registry_variant registry_wrong_pk 'ALTER TABLE schema_migration DROP CONSTRAINT schema_migration_pkey; ALTER TABLE schema_migration ADD PRIMARY KEY (filename, checksum);'
registry_variant registry_extra_constraint 'ALTER TABLE schema_migration ADD CONSTRAINT extra_registry_check CHECK (length(filename)>0);'
registry_variant registry_not_valid "ALTER TABLE schema_migration DROP CONSTRAINT chk_schema_migration_checksum; ALTER TABLE schema_migration ADD CONSTRAINT chk_schema_migration_checksum CHECK (checksum ~ '^[0-9a-f]{64}$') NOT VALID;"
registry_variant registry_deferrable_pk 'ALTER TABLE schema_migration DROP CONSTRAINT schema_migration_pkey; ALTER TABLE schema_migration ADD CONSTRAINT schema_migration_pkey PRIMARY KEY (filename) DEFERRABLE INITIALLY IMMEDIATE;'
registry_variant registry_initially_deferred_pk 'ALTER TABLE schema_migration DROP CONSTRAINT schema_migration_pkey; ALTER TABLE schema_migration ADD CONSTRAINT schema_migration_pkey PRIMARY KEY (filename) DEFERRABLE INITIALLY DEFERRED;'
registry_variant registry_no_inherit_check "ALTER TABLE schema_migration DROP CONSTRAINT chk_schema_migration_checksum; ALTER TABLE schema_migration ADD CONSTRAINT chk_schema_migration_checksum CHECK (checksum ~ '^[0-9a-f]{64}$') NO INHERIT;"
registry_variant registry_reordered_columns "DROP TABLE schema_migration; CREATE TABLE schema_migration (filename varchar(255) NOT NULL, applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, checksum char(64) NOT NULL, CONSTRAINT schema_migration_pkey PRIMARY KEY (filename), CONSTRAINT chk_schema_migration_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'));"

schema_variant() {
  local name="$1" sql="$2"
  printf 'Checking fail-closed schema variant: %s\n' "${name}"
  prepare_registered_v4 "${name}"
  psql_exec "${name}" -c "${sql}" >/dev/null
  run_image_failed "${name}" "${name}" "${JAR}"
  log_contains "${name}" 'registered migration schema is partial or corrupt'
}
schema_variant guard_extra_column 'ALTER TABLE portable_import_guard ADD COLUMN extra integer;'
schema_variant guard_wrong_column 'ALTER TABLE portable_import_guard ALTER COLUMN id TYPE bigint;'
schema_variant guard_wrong_pk_columns 'ALTER TABLE portable_import_guard DROP CONSTRAINT portable_import_guard_pkey; ALTER TABLE portable_import_guard ADD COLUMN other integer NOT NULL DEFAULT 1; ALTER TABLE portable_import_guard ADD PRIMARY KEY (id, other);'
schema_variant guard_wrong_check 'ALTER TABLE portable_import_guard DROP CONSTRAINT chk_portable_import_guard_singleton; ALTER TABLE portable_import_guard ADD CONSTRAINT chk_portable_import_guard_singleton CHECK (id >= 1);'
schema_variant guard_identity 'ALTER TABLE portable_import_guard ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;'
schema_variant guard_extra_index 'CREATE INDEX extra_portable_import_guard_id ON portable_import_guard(id);'
schema_variant operation_wrong_pk_columns 'ALTER TABLE portable_import_operation DROP CONSTRAINT portable_import_operation_pkey; ALTER TABLE portable_import_operation ADD PRIMARY KEY (job_id, preview_token);'
schema_variant operation_wrong_unique_columns 'ALTER TABLE portable_import_operation DROP CONSTRAINT uk_portable_import_preview; ALTER TABLE portable_import_operation ADD CONSTRAINT uk_portable_import_preview UNIQUE (preview_token, user_id);'
schema_variant operation_extra_constraint 'ALTER TABLE portable_import_operation ADD CONSTRAINT extra_operation_check CHECK (user_id > 0);'
schema_variant operation_reordered_columns 'DROP TABLE portable_import_operation; CREATE TABLE portable_import_operation (job_id varchar(64) PRIMARY KEY, preview_token varchar(64) NOT NULL, user_id bigint NOT NULL, started_at timestamp NOT NULL, site_version integer NOT NULL, committed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, created_at timestamp NOT NULL, CONSTRAINT uk_portable_import_preview UNIQUE (preview_token)); CREATE INDEX idx_portable_import_user_committed ON portable_import_operation (user_id, committed_at DESC);'
schema_variant operation_identity 'ALTER TABLE portable_import_operation ALTER COLUMN site_version ADD GENERATED BY DEFAULT AS IDENTITY;'
schema_variant operation_generated "DROP TABLE portable_import_operation; CREATE TABLE portable_import_operation (job_id varchar(64) PRIMARY KEY, preview_token varchar(64) NOT NULL, user_id bigint NOT NULL, created_at timestamp NOT NULL, started_at timestamp GENERATED ALWAYS AS (created_at) STORED NOT NULL, committed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, site_version integer NOT NULL, CONSTRAINT uk_portable_import_preview UNIQUE (preview_token)); CREATE INDEX idx_portable_import_user_committed ON portable_import_operation (user_id, committed_at DESC);"
schema_variant operation_job_id_noncanonical_collation 'ALTER TABLE portable_import_operation ALTER COLUMN job_id TYPE varchar(64) COLLATE "C";'
schema_variant operation_preview_token_noncanonical_collation 'ALTER TABLE portable_import_operation ALTER COLUMN preview_token TYPE varchar(64) COLLATE "C";'
schema_variant index_unique 'DROP INDEX idx_portable_import_user_committed; CREATE UNIQUE INDEX idx_portable_import_user_committed ON portable_import_operation(user_id, committed_at DESC);'
schema_variant index_partial 'DROP INDEX idx_portable_import_user_committed; CREATE INDEX idx_portable_import_user_committed ON portable_import_operation(user_id, committed_at DESC) WHERE user_id > 0;'
schema_variant index_expression 'DROP INDEX idx_portable_import_user_committed; CREATE INDEX idx_portable_import_user_committed ON portable_import_operation(user_id, (committed_at::date) DESC);'
schema_variant index_wrong_order 'DROP INDEX idx_portable_import_user_committed; CREATE INDEX idx_portable_import_user_committed ON portable_import_operation(committed_at DESC, user_id);'
schema_variant index_wrong_sort 'DROP INDEX idx_portable_import_user_committed; CREATE INDEX idx_portable_import_user_committed ON portable_import_operation(user_id DESC, committed_at DESC);'
schema_variant index_wrong_nulls 'DROP INDEX idx_portable_import_user_committed; CREATE INDEX idx_portable_import_user_committed ON portable_import_operation(user_id, committed_at DESC NULLS LAST);'
schema_variant index_include 'DROP INDEX idx_portable_import_user_committed; CREATE INDEX idx_portable_import_user_committed ON portable_import_operation(user_id, committed_at DESC) INCLUDE (job_id);'
schema_variant index_extra 'CREATE INDEX extra_portable_import_index ON portable_import_operation(job_id);'
schema_variant index_extra_expression_only 'CREATE INDEX extra_portable_import_expression ON portable_import_operation ((lower(job_id)));'

mkdir -p "${TMP_DIR}/BOOT-INF/classes/database/migrations"
printf '%s\n' '-- deliberately corrupted migration' > \
  "${TMP_DIR}/BOOT-INF/classes/database/migrations/${MIGRATION}"
cp -- "${JAR}" "${TMP_DIR}/corrupt.jar"
docker run --rm -v "${TMP_DIR}:/work" -w /work eclipse-temurin:17-jdk-jammy \
  jar --update --file corrupt.jar -C . "BOOT-INF/classes/database/migrations/${MIGRATION}"
prepare_v3 classpath_mismatch
run_image_failed classpath-checksum-mismatch classpath_mismatch "${TMP_DIR}/corrupt.jar"
log_contains classpath-checksum-mismatch 'classpath migration checksum mismatch'
[[ "$(psql_exec classpath_mismatch -Atc "SELECT count(*) FROM schema_migration WHERE filename='${MIGRATION}'")" == 0 ]]

fresh_unconfigured_container="$(container_name fresh-unconfigured)"
containers+=("${fresh_unconfigured_container}"); ((++invocations))
docker run -d --name "${fresh_unconfigured_container}" --network "${NETWORK}" \
  --label "com.docker.compose.project=${PROJECT}" \
  -e SPRING_PROFILES_ACTIVE=prod -e NAV_DATABASE_SOURCE=UNCONFIGURED \
  -e NAV_REDIS_SOURCE=UNCONFIGURED -e CACHE_TYPE=redis \
  -e NAV_BOOTSTRAP_ENABLED=false -e SERVER_PORT=0 \
  -e JWT_SECRET='migration-test-jwt-secret-with-more-than-thirty-two-bytes' \
  "${IMAGE}" >/dev/null
wait_for_log fresh-unconfigured 'migration startup skipped for fresh unconfigured installation' ||
  fail "fresh unconfigured Docker startup was broken"
wait_for_log fresh-unconfigured 'Started NavApplication' ||
  fail "fresh unconfigured Docker startup did not remain available"

(( invocations == 57 )) || fail "unexpected migration artifact invocation count: ${invocations}"
printf 'PostgreSQL migration artifact tests passed: %d invocations (startup-gate=1, concurrent=2, Docker apply=1, Host apply=1, idempotent=1, fail-closed=50, unconfigured=1); preserved-v3-tables=6, preserved-v3-rows=6, migration-registrations=1, PostgreSQL-major=17; Docker/Host JAR SHA=%s.\n' "${invocations}" "${INPUT_JAR_SHA}"
