#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/java-env.sh
source "$SCRIPT_DIR/java-env.sh"

assert_selected_java() {
  local expected_home="$1"
  [[ "$JAVA_HOME" == "$expected_home" ]]
  [[ "$PATH" == "$expected_home/bin:"* ]]
}

make_fake_java() {
  local java_home="$1"
  local version="$2"

  mkdir -p "$java_home/bin"
  sed \
    -e "s|@JAVA_HOME@|$java_home|g" \
    -e "s|@VERSION@|$version|g" \
    "$SCRIPT_DIR/test-fixtures/fake-java.sh.in" >"$java_home/bin/java"
  chmod +x "$java_home/bin/java"
}

test_current_java_17_or_newer_is_selected() {
  local fixture_root="$1/current"
  local current_home="$fixture_root/jdk-17"

  make_fake_java "$current_home" "17.0.12"
  unset JAVA_HOME
  PATH="$current_home/bin:/usr/bin:/bin"

  select_java_17_or_newer

  assert_selected_java "$current_home"
}

test_current_java_99_is_selected() {
  local fixture_root="$1/current-99"
  local current_home="$fixture_root/jdk-99"

  make_fake_java "$current_home" "99.0.1"
  unset JAVA_HOME
  PATH="$current_home/bin:/usr/bin:/bin"

  select_java_17_or_newer

  assert_selected_java "$current_home"
}

test_macos_fallback_prefers_jdk_21() {
  local fixture_root="$1/prefer-21"
  local old_home="$fixture_root/jdk-8"
  local jdk_17_home="$fixture_root/jdk-17"
  local jdk_21_home="$fixture_root/jdk-21"

  make_fake_java "$old_home" "1.8.0_491"
  make_fake_java "$jdk_17_home" "17.0.12"
  make_fake_java "$jdk_21_home" "21.0.4"
  unset JAVA_HOME
  PATH="$old_home/bin:/usr/bin:/bin"
  platform_name() { printf '%s\n' "Darwin"; }
  macos_java_home_is_available() { return 0; }
  macos_java_home_for_version() {
    case "$1" in
      21) printf '%s\n' "$jdk_21_home" ;;
      17) printf '%s\n' "$jdk_17_home" ;;
      *) return 1 ;;
    esac
  }

  select_java_17_or_newer

  assert_selected_java "$jdk_21_home"
}

test_macos_fallback_uses_jdk_17_when_21_is_missing() {
  local fixture_root="$1/fallback-17"
  local old_home="$fixture_root/jdk-8"
  local jdk_17_home="$fixture_root/jdk-17"

  make_fake_java "$old_home" "1.8.0_491"
  make_fake_java "$jdk_17_home" "17.0.12"
  unset JAVA_HOME
  PATH="$old_home/bin:/usr/bin:/bin"
  platform_name() { printf '%s\n' "Darwin"; }
  macos_java_home_is_available() { return 0; }
  macos_java_home_for_version() {
    case "$1" in
      21) return 1 ;;
      17) printf '%s\n' "$jdk_17_home" ;;
      *) return 1 ;;
    esac
  }

  select_java_17_or_newer

  assert_selected_java "$jdk_17_home"
}

test_macos_rejects_candidate_with_wrong_requested_major() {
  local fixture_root="$1/exact-major"
  local old_home="$fixture_root/jdk-8"
  local wrong_home="$fixture_root/jdk-99"
  local jdk_17_home="$fixture_root/jdk-17"

  make_fake_java "$old_home" "1.8.0_491"
  make_fake_java "$wrong_home" "99.0.1"
  make_fake_java "$jdk_17_home" "17.0.12"
  unset JAVA_HOME
  PATH="$old_home/bin:/usr/bin:/bin"
  platform_name() { printf '%s\n' "Darwin"; }
  macos_java_home_is_available() { return 0; }
  macos_java_home_for_version() {
    case "$1" in
      21) printf '%s\n' "$wrong_home" ;;
      17) printf '%s\n' "$jdk_17_home" ;;
      *) return 1 ;;
    esac
  }

  select_java_17_or_newer

  assert_selected_java "$jdk_17_home"
}

test_macos_java_home_unavailable_fails_before_lookup() {
  local fixture_root="$1/unavailable"
  local old_home="$fixture_root/jdk-8"
  local jdk_21_home="$fixture_root/jdk-21"
  local error_output

  make_fake_java "$old_home" "1.8.0_491"
  make_fake_java "$jdk_21_home" "21.0.4"
  unset JAVA_HOME
  PATH="$old_home/bin:/usr/bin:/bin"
  platform_name() { printf '%s\n' "Darwin"; }
  macos_java_home_is_available() { return 1; }
  macos_java_home_for_version() { printf '%s\n' "$jdk_21_home"; }

  if error_output="$(select_java_17_or_newer 2>&1)"; then
    echo "Java selection ignored unavailable macOS java_home" >&2
    return 1
  fi
  [[ "$error_output" == "Java 17 or newer is required; install JDK 21 or JDK 17 and retry." ]]
}

test_missing_java_17_fails_clearly() {
  local fixture_root="$1/missing"
  local old_home="$fixture_root/jdk-8"
  local error_output

  make_fake_java "$old_home" "1.8.0_491"
  unset JAVA_HOME
  PATH="$old_home/bin:/usr/bin:/bin"
  platform_name() { printf '%s\n' "Linux"; }

  if error_output="$(select_java_17_or_newer 2>&1)"; then
    echo "Java selection unexpectedly accepted Java 8" >&2
    return 1
  fi
  [[ "$error_output" == "Java 17 or newer is required; install JDK 21 or JDK 17 and retry." ]]
}

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/eventra-java-selection.XXXXXX")"
trap 'rm -rf "$fixture_root"' EXIT

test_current_java_17_or_newer_is_selected "$fixture_root"
test_current_java_99_is_selected "$fixture_root"
test_macos_fallback_prefers_jdk_21 "$fixture_root"
test_macos_fallback_uses_jdk_17_when_21_is_missing "$fixture_root"
test_macos_rejects_candidate_with_wrong_requested_major "$fixture_root"
test_macos_java_home_unavailable_fails_before_lookup "$fixture_root"
test_missing_java_17_fails_clearly "$fixture_root"

echo "Java selection tests passed"
