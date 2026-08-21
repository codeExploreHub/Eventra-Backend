#!/usr/bin/env bash
set -euo pipefail

curl --fail --silent --show-error http://localhost:8080/actuator/health >/dev/null
curl --fail --silent --show-error http://localhost:8080/v3/api-docs >/dev/null
echo "backend smoke checks passed"
