#!/usr/bin/env bash

java_major_for() {
  local java_command="$1"
  local version_output

  version_output="$("$java_command" -version 2>&1)" || return 1
  if [[ "$version_output" =~ version[[:space:]]+\"1\.([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "$version_output" =~ version[[:space:]]+\"([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

java_home_for() {
  local java_command="$1"
  local settings_output
  local line

  settings_output="$("$java_command" -XshowSettings:properties -version 2>&1)" || return 1
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*java\.home[[:space:]]*=[[:space:]]*(.+)$ ]]; then
      printf '%s\n' "${BASH_REMATCH[1]}"
      return 0
    fi
  done <<<"$settings_output"
  return 1
}

java_home_is_17_or_newer() {
  local candidate_home="$1"
  local major

  [[ -n "$candidate_home" ]] || return 1
  [[ -x "$candidate_home/bin/java" ]] || return 1
  major="$(java_major_for "$candidate_home/bin/java")" || return 1
  (( major >= 17 ))
}

platform_name() {
  uname -s
}

macos_java_home_for_version() {
  /usr/libexec/java_home -F -v "$1"
}

macos_java_home_is_available() {
  [[ -x /usr/libexec/java_home ]]
}

java_home_has_major() {
  local candidate_home="$1"
  local expected_major="$2"
  local actual_major

  [[ -n "$candidate_home" ]] || return 1
  [[ -x "$candidate_home/bin/java" ]] || return 1
  actual_major="$(java_major_for "$candidate_home/bin/java")" || return 1
  [[ "$actual_major" == "$expected_major" ]]
}

activate_java_home() {
  local selected_home="$1"

  export JAVA_HOME="$selected_home"
  export PATH="$JAVA_HOME/bin:$PATH"
}

select_java_17_or_newer() {
  local current_java=""
  local current_major=""
  local candidate_home=""
  local requested_version

  current_java="$(command -v java 2>/dev/null || true)"
  if [[ -n "$current_java" ]]; then
    current_major="$(java_major_for "$current_java" || true)"
    if [[ "$current_major" =~ ^[0-9]+$ ]] && (( current_major >= 17 )); then
      candidate_home="$(java_home_for "$current_java" || true)"
      if java_home_is_17_or_newer "$candidate_home"; then
        activate_java_home "$candidate_home"
        return 0
      fi
    fi
  fi

  if [[ "$(platform_name)" == "Darwin" ]] && macos_java_home_is_available; then
    for requested_version in 21 17; do
      candidate_home="$(macos_java_home_for_version "$requested_version" 2>/dev/null || true)"
      if java_home_has_major "$candidate_home" "$requested_version"; then
        activate_java_home "$candidate_home"
        return 0
      fi
    done
  fi

  echo "Java 17 or newer is required; install JDK 21 or JDK 17 and retry." >&2
  return 1
}
