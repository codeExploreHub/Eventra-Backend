# Backend known pitfalls

## Unverified Maven environment

Ad-hoc Maven commands can bypass the repository's public-only settings or use
an unsupported Java runtime. Prefer `scripts/test-local.sh`, which applies
`.mvn/settings-public.xml` and selects an installed JDK 21 or 17 on macOS when
needed.

## Secret leakage

`JWT_SECRET` is runtime input and must contain at least 64 characters. Never
copy it into source, documentation, logs, comments, or evidence.

## Untrusted env files

`scripts/run-local.sh [--env-file path]` sources the optional file as shell
input. Use only a user-created, trusted local file and never point it at an
untrusted artifact.
