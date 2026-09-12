#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:8080}"
BACKEND_BASE_URL="${BACKEND_BASE_URL%/}"
if ! curl --fail --silent --show-error "$BACKEND_BASE_URL/actuator/health" |
  od -An -v -tu1 |
  LC_ALL=C awk -f "$SCRIPT_DIR/health-status.awk"; then
  echo "backend health check failed: expected successful HTTP and JSON status UP" >&2
  exit 1
fi
curl --fail --silent --show-error "$BACKEND_BASE_URL/v3/api-docs" >/dev/null
echo "backend smoke checks passed"
