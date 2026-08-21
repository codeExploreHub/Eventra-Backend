#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=scripts/java-env.sh
source "$SCRIPT_DIR/java-env.sh"

for argument in "$@"; do
  if [[ "$argument" != -* ]]; then
    echo "usage: scripts/test-local.sh [Maven -D/-P/... options]" >&2
    exit 2
  fi
done

select_java_17_or_newer
cd "$REPOSITORY_ROOT"
exec ./mvnw -s .mvn/settings-public.xml "$@" test
