#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/java-env.sh
source "$SCRIPT_DIR/java-env.sh"

if [[ "${1:-}" == "--env-file" ]]; then
  if [[ -z "${2:-}" || ! -f "$2" ]]; then
    echo "--env-file requires an existing file" >&2
    exit 2
  fi
  set -a
  source "$2"
  set +a
elif [[ $# -ne 0 ]]; then
  echo "usage: scripts/run-local.sh [--env-file path]" >&2
  exit 2
fi

if [[ -z "${JWT_SECRET:-}" ]]; then
  echo "missing required variable: JWT_SECRET" >&2
  exit 2
fi
if [[ ${#JWT_SECRET} -lt 64 ]]; then
  echo "JWT_SECRET must contain at least 64 characters" >&2
  exit 2
fi

select_java_17_or_newer

if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port 8080 is already in use; inspect it with: lsof -nP -iTCP:8080 -sTCP:LISTEN" >&2
  exit 2
fi

exec ./mvnw -s .mvn/settings-public.xml \
  -Dspring-boot.run.arguments=--spring.profiles.active=local \
  spring-boot:run
