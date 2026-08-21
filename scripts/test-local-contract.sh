#!/usr/bin/env bash
set -euo pipefail

test -x ./mvnw
grep -Fq 'repo.maven.apache.org/maven2' .mvn/settings-public.xml
grep -Fq 'enabled: false' src/main/resources/application-local.yml
grep -Fq '${JWT_SECRET}' src/main/resources/application.yml
grep -Fq 'JWT_SECRET=' .env.example
grep -Fq -- '--spring.profiles.active=local' scripts/run-local.sh
if grep -Eq 'artifactory\.intra|<username>|<password>' .mvn/settings-public.xml; then
  echo "private Maven configuration must not be committed" >&2
  exit 1
fi
