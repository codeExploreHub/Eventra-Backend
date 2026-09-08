#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if ! curl --fail --silent --show-error http://localhost:8080/actuator/health |
  iconv -f UTF-8 -t UTF-8 2>/dev/null |
  LC_ALL=C awk -f "$SCRIPT_DIR/health-status.awk"; then
  echo "backend health check failed: expected successful HTTP and JSON status UP" >&2
  exit 1
fi
curl --fail --silent --show-error http://localhost:8080/v3/api-docs >/dev/null
echo "backend smoke checks passed"
