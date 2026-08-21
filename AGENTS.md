# Backend Agent Guide

## Ownership and coordination

Work only on the backend task paths assigned to you. Preserve other agents'
changes and do not edit the frontend, shared plans, ledgers, or another
worktree. Return the exact commit SHA, changed paths, verification commands
and exit codes to the delivery lead and QA/reviewer.

## Backend rules

- Target Java 17 and Spring Boot 3 conventions.
- Treat published API contracts as frozen unless the assigned task explicitly
  changes them; retain compatible routes, payloads, status codes, and security
  behavior.
- Use test-driven development: add a focused failing test, observe the
  expected failure, implement the minimum behavior, then rerun tests.
- Use `.mvn/settings-public.xml` for Maven commands. It must mirror only the
  public HTTPS Maven Central repository; never add company repositories or
  credentials.

## Secrets and local development

- Never commit, log, print, or report real secrets. Keep `.env` and
  `.env.local` ignored.
- `JWT_SECRET` is required for local startup and must contain at least 64
  characters. Multica supplies it through agent custom environment; do not
  copy a secret into a worktree.
- Use `scripts/run-local.sh [--env-file path]` for local startup and
  `scripts/smoke-local.sh` only after confirming the service is running. The
  optional env file must be user-created and trusted local shell input because
  the startup script sources it; never point it at an untrusted file.
