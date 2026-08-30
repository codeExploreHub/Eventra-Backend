#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd "${script_dir}/.." && pwd -P)"
migration_file="${1:-${repository_root}/src/main/resources/db/migration/V3__username_normalized_uniqueness.sql}"

for required_command in initdb pg_ctl psql; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    echo "Required PostgreSQL command not found: ${required_command}" >&2
    exit 1
  fi
done

if [[ ! -r "${migration_file}" ]]; then
  echo "Migration file not found: ${migration_file}" >&2
  exit 1
fi

postgres_test_root="$(mktemp -d "${TMPDIR:-/tmp}/eventra-username-postgres.XXXXXX")"
postgres_data_dir="${postgres_test_root}/data"
postgres_socket_dir="${postgres_test_root}/socket"
mkdir -p "${postgres_socket_dir}"

cleanup() {
  if [[ -f "${postgres_data_dir}/postmaster.pid" ]]; then
    pg_ctl -D "${postgres_data_dir}" -m immediate stop >/dev/null 2>&1 || true
  fi
  rm -rf "${postgres_test_root}"
}
trap cleanup EXIT

initdb \
  -D "${postgres_data_dir}" \
  --encoding=UTF8 \
  --locale=C \
  --auth=trust >/dev/null
pg_ctl \
  -D "${postgres_data_dir}" \
  -o "-F -c listen_addresses='' -k ${postgres_socket_dir}" \
  -w start >/dev/null

psql_command=(psql -X -v ON_ERROR_STOP=1 -h "${postgres_socket_dir}" -d postgres)

postgres_major_version="$("${psql_command[@]}" -At -c "SHOW server_version_num")"
if (( postgres_major_version < 170000 || postgres_major_version >= 180000 )); then
  echo "PostgreSQL 17 is required; server_version_num=${postgres_major_version}" >&2
  exit 1
fi

reset_users_table() {
  "${psql_command[@]}" -q -c \
    "DROP TABLE IF EXISTS users; CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE);"
}

reset_users_table_with_collation() {
  local quoted_collation_name="$1"
  "${psql_command[@]}" -q -c \
    "DROP TABLE IF EXISTS users; CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(50) COLLATE ${quoted_collation_name} NOT NULL UNIQUE);"
}

apply_migration() {
  # Match UsernameMigrationInitializer: the advisory lock and every migration
  # statement share one transaction, so concurrent application starts serialize.
  "${psql_command[@]}" -q --single-transaction \
    -c "SELECT pg_advisory_xact_lock(8247719306703)" \
    -f "${migration_file}"
}

assert_failed_with() {
  local expected_diagnostic="$1"
  shift
  local failure_log="${postgres_test_root}/expected-failure.log"

  if "$@" >"${failure_log}" 2>&1; then
    echo "Expected command to fail with ${expected_diagnostic}" >&2
    exit 1
  fi
  if ! grep -qi "${expected_diagnostic}" "${failure_log}"; then
    echo "Failure did not include diagnostic ${expected_diagnostic}" >&2
    exit 1
  fi
}

reset_users_table
"${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username) VALUES
    (1, chr(9) || chr(31) || ' Alice ' || chr(13) || chr(10)),
    (2, 'Bob');"
apply_migration

normalized_rows="$("${psql_command[@]}" -At -c \
  "SELECT username || '|' || username_normalized FROM users ORDER BY id")"
if [[ "${normalized_rows}" != $'Alice|alice\nBob|bob' ]]; then
  echo "Unexpected PostgreSQL normalization result" >&2
  exit 1
fi

# Startup initialization can safely execute the same script again.
apply_migration

# Separate application instances can initialize the same schema concurrently.
concurrent_first_log="${postgres_test_root}/concurrent-first.log"
concurrent_second_log="${postgres_test_root}/concurrent-second.log"
apply_migration >"${concurrent_first_log}" 2>&1 &
concurrent_first_pid=$!
apply_migration >"${concurrent_second_log}" 2>&1 &
concurrent_second_pid=$!
concurrent_failure=0
wait "${concurrent_first_pid}" || concurrent_failure=1
wait "${concurrent_second_pid}" || concurrent_failure=1
if (( concurrent_failure != 0 )); then
  echo "Concurrent PostgreSQL username migrations failed" >&2
  exit 1
fi

assert_failed_with "ck_users_username_ascii" \
  "${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username, username_normalized) VALUES (3, 'bad-name', 'bad-name')"
assert_failed_with "ck_users_username_normalized_consistent" \
  "${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username, username_normalized) VALUES (4, 'Charlie', 'wrong')"

# Isolate the username length invariant from the ASCII invariant.
"${psql_command[@]}" -q -c \
  "ALTER TABLE users DROP CONSTRAINT ck_users_username_ascii"
for underlength in 0 1 2; do
  assert_failed_with "ck_users_username_length" \
    "${psql_command[@]}" -q -c \
    "INSERT INTO users (id, username, username_normalized)
     VALUES (5, repeat('a', ${underlength}), repeat('a', ${underlength}))"
done

"${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username, username_normalized) VALUES
    (6, 'Abc', 'abc'),
    (7, repeat('A', 50), repeat('a', 50));"
boundary_lengths="$("${psql_command[@]}" -At -c \
  "SELECT character_length(username) || '|' || character_length(username_normalized)
   FROM users WHERE id IN (6, 7) ORDER BY id")"
if [[ "${boundary_lengths}" != $'3|3\n50|50' ]]; then
  echo "PostgreSQL rejected or altered a valid username length boundary" >&2
  exit 1
fi

# Isolate the normalized-key length invariant from the consistency invariant.
"${psql_command[@]}" -q -c \
  "ALTER TABLE users DROP CONSTRAINT ck_users_username_normalized_consistent"
for underlength in 0 1 2; do
  assert_failed_with "ck_users_username_normalized_length" \
    "${psql_command[@]}" -q -c \
    "INSERT INTO users (id, username, username_normalized)
     VALUES (8, 'Def', repeat('a', ${underlength}))"
done

reset_users_table
"${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username) VALUES (1, chr(9) || chr(31) || chr(32))"
assert_failed_with "ck_users_username_length" apply_migration
whitespace_only_legacy_hex="$("${psql_command[@]}" -At -c \
  "SELECT encode(convert_to(username, 'UTF8'), 'hex') FROM users WHERE id = 1")"
if [[ "${whitespace_only_legacy_hex}" != "091f20" ]]; then
  echo "PostgreSQL migration rewrote a whitespace-only legacy username before failing" >&2
  exit 1
fi

reset_users_table
"${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username) VALUES (1, chr(304) || 'XX'), (2, 'ValidUser')"
assert_failed_with "ck_users_username_ascii" apply_migration

reset_users_table
"${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username) VALUES (1, 'CaseUser'), (2, 'caseuser')"
assert_failed_with "uk_users_username_normalized" apply_migration

turkish_collation="$("${psql_command[@]}" -At -c \
  "SELECT quote_ident(collname) FROM pg_collation WHERE collname LIKE 'tr_TR%' ORDER BY collname LIMIT 1")"
if [[ -z "${turkish_collation}" ]]; then
  echo "A Turkish PostgreSQL collation is required for the Locale.ROOT regression" >&2
  exit 1
fi
reset_users_table_with_collation "${turkish_collation}"
"${psql_command[@]}" -q -c \
  "INSERT INTO users (id, username) VALUES (1, 'IUSER'), (2, 'OtherUser')"
apply_migration
root_locale_rows="$("${psql_command[@]}" -At -c \
  "SELECT username_normalized FROM users ORDER BY id")"
if [[ "${root_locale_rows}" != $'iuser\notheruser' ]]; then
  echo "PostgreSQL collation disagrees with Java Locale.ROOT normalization" >&2
  exit 1
fi

echo "PostgreSQL username migration regression passed"
