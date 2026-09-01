# Backend repository map

This repository is the authoritative Eventra backend. It owns the HTTP API,
security behavior, persistence, migrations, backend tests, and backend local
runtime scripts. Frontend and shared delivery-control changes belong in the
sibling `Eventra` repository and its assigned worktree.

- `src/main/java/` owns Spring application code.
- `src/main/resources/` owns runtime configuration and database migrations.
- `src/test/` owns backend tests.
- `scripts/` owns local test, start, and smoke commands.
- `.mvn/settings-public.xml` is the public-only Maven settings file.
- `docs/agent-knowledge/` is the canonical backend repository knowledge.

Verify boundaries against `AGENTS.md` before changing code.
