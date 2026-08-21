#!/usr/bin/env bash
set -euo pipefail

test -x ./mvnw
test -x scripts/java-env.sh
test -x scripts/test-java-selection.sh
test -x scripts/test-local-wrapper.sh
test -x scripts/test-local.sh
grep -Fq 'repo.maven.apache.org/maven2' .mvn/settings-public.xml
grep -Fq 'enabled: false' src/main/resources/application-local.yml
grep -Fq '${JWT_SECRET}' src/main/resources/application.yml
grep -Fq 'JWT_SECRET=' .env.example
grep -Fq 'source "$SCRIPT_DIR/java-env.sh"' scripts/run-local.sh
grep -Fq 'source "$SCRIPT_DIR/java-env.sh"' scripts/test-local.sh
grep -Fq '/usr/libexec/java_home -F -v "$1"' scripts/java-env.sh
grep -Fq -- '--spring.profiles.active=local' scripts/run-local.sh
if grep -Eq 'artifactory\.intra|<username>|<password>' .mvn/settings-public.xml; then
  echo "private Maven configuration must not be committed" >&2
  exit 1
fi

scripts/test-java-selection.sh
scripts/test-local-wrapper.sh
