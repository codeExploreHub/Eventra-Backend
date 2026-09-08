#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
cat >"$TEST_DIR/curl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == *--fail* ]] || exit 99
case "${!#}" in
  http://localhost:8080/actuator/health)
    printf '%s' "$HEALTH_BODY"
    exit "$HEALTH_EXIT" ;;
  http://localhost:8080/v3/api-docs)
    echo called >"$DOCS_CALL"
    exit "$DOCS_EXIT" ;;
  *) exit 99 ;;
esac
FAKE
chmod +x "$TEST_DIR/curl"
export PATH="$TEST_DIR:$PATH" DOCS_CALL="$TEST_DIR/docs"
failures=0
run_case() {
  local name="$1" expected="$2" actual=0
  export HEALTH_BODY="$3" HEALTH_EXIT="${4:-0}" DOCS_EXIT="${5:-0}"
  : >"$DOCS_CALL"
  bash "$SCRIPT_DIR/smoke-local.sh" >"$TEST_DIR/output" 2>&1 || actual=$?
  if { [[ "$expected" == pass ]] && [[ "$actual" != 0 ]]; } ||
     { [[ "$expected" == fail ]] && [[ "$actual" == 0 ]]; }; then
    echo "FAIL: $name (unexpected exit $actual)"
    failures=$((failures + 1))
  elif [[ "$expected" == pass && ! -s "$DOCS_CALL" ]]; then
    echo "FAIL: $name (API docs not checked)"
    failures=$((failures + 1))
  elif [[ -n "$HEALTH_BODY" ]] && LC_ALL=C grep -Fq "$HEALTH_BODY" "$TEST_DIR/output"; then
    echo "FAIL: $name (response body disclosed)"
    failures=$((failures + 1))
  else
    echo "PASS: $name"
  fi
}
run_case up pass '{"status":"UP"}'
run_case down fail '{"status":"DOWN"}'
run_case out_of_service fail '{"status":"OUT_OF_SERVICE"}'
run_case unknown fail '{"status":"UNKNOWN"}'
run_case lowercase fail '{"status":"up"}'
run_case missing fail '{"details":{"status":"UP"}}'
run_case null_status fail '{"status":null}'
run_case numeric_status fail '{"status":1}'
run_case array_root fail '[{"status":"UP"}]'
run_case invalid_json fail '{"status":"UP",}'
run_case trailing_data fail '{"status":"UP"} garbage'
run_case empty fail ''
run_case http_failure fail '{"status":"UP"}' 22
run_case connection_failure fail '' 7
run_case docs_failure fail '{"status":"UP"}' 0 22
run_case nested_details pass '{"details":{"status":"DOWN","values":[true,false,null,-1.2e+3]},"status":"UP"}'
run_case escaped_status pass '{"sta\u0074us":"\u0055P"}'
run_case invalid_escape fail '{"status":"UP","detail":"\q"}'
run_case invalid_number fail '{"status":"UP","detail":01}'
run_case invalid_literal fail '{"status":"UP","detail":NaN}'
run_case duplicate_status fail '{"status":"DOWN","status":"UP"}'
run_case invalid_utf8 fail $'{"status":"UP","detail":"\xff"}'
run_case unicode_details pass '{"status":"UP","details":"健康"}'
run_case del_in_string pass $'{"status":"UP","details":"\x7f"}'
run_case raw_control fail $'{"status":"UP","details":"\x01"}'
run_case sensitive_details pass '{"status":"UP","details":"synthetic-private-marker"}'
if grep -Fq synthetic-private-marker "$TEST_DIR/output"; then
  echo 'FAIL: health details disclosed'
  failures=$((failures + 1))
fi
[[ "$failures" == 0 ]]
