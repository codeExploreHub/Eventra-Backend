#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/eventra-test-local.XXXXXX")"
trap 'rm -rf "$fixture_root"' EXIT

mkdir -p "$fixture_root/repository/scripts" "$fixture_root/repository/.mvn" \
  "$fixture_root/jdk-17/bin"
cp "$SCRIPT_DIR/test-local.sh" "$SCRIPT_DIR/java-env.sh" \
  "$fixture_root/repository/scripts/"

sed \
  -e "s|@JAVA_HOME@|$fixture_root/jdk-17|g" \
  -e 's|@VERSION@|17.0.12|g' \
  "$SCRIPT_DIR/test-fixtures/fake-java.sh.in" >"$fixture_root/jdk-17/bin/java"
chmod +x "$fixture_root/jdk-17/bin/java"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf "%s\\n" "$@"' >"$fixture_root/repository/mvnw"
chmod +x "$fixture_root/repository/mvnw"

wrapper="$fixture_root/repository/scripts/test-local.sh"
test_path="$fixture_root/jdk-17/bin:/usr/bin:/bin"

assert_output() {
  local actual="$1"
  local expected="$2"
  local description="$3"

  if [[ "$actual" != "$expected" ]]; then
    echo "$description" >&2
    return 1
  fi
}

output="$(PATH="$test_path" "$wrapper")"
assert_output "$output" $'-s\n.mvn/settings-public.xml\ntest' \
  "test-local changed the default Maven contract"

output="$(PATH="$test_path" "$wrapper" -Dtest=FeedbackControllerTests)"
assert_output "$output" \
  $'-s\n.mvn/settings-public.xml\n-Dtest=FeedbackControllerTests\ntest' \
  "test-local did not forward a focused Maven option before the test goal"

if output="$(PATH="$test_path" "$wrapper" package 2>&1)"; then
  echo "test-local accepted a Maven goal" >&2
  exit 1
fi
[[ "$output" == "usage: scripts/test-local.sh [Maven -D/-P/... options]" ]]

echo "test-local wrapper tests passed"
