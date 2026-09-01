# Backend testing guide

- `scripts/test-local.sh` is the standard backend quality command. It uses the
  repository's public-only Maven settings and an eligible installed JDK.
- `scripts/run-local.sh [--env-file path]` starts the backend with the local
  Spring profile. The required `JWT_SECRET` remains runtime-only input.
- `scripts/smoke-local.sh` checks `/actuator/health` only after the service is
  confirmed running.

For behavior changes, first add a focused failing test and observe the expected
RED result. Implement the minimum behavior, rerun the focused test, then run
`scripts/test-local.sh`. Record exact commands, exit codes, branch, and commit
SHA without recording secrets.
