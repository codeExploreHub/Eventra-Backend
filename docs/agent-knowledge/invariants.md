# Backend invariants

- Target Java 17 and Spring Boot 3 conventions.
- Treat published API contracts as frozen unless the assigned task explicitly
  changes them.
- Use `.mvn/settings-public.xml`; it may mirror only public HTTPS Maven Central
  and must never contain company repositories or credentials.
- Never commit, log, print, or report real secrets. Keep `.env` and
  `.env.local` ignored.
- Local startup requires `JWT_SECRET` with at least 64 characters. Multica
  supplies it only to authorized roles; do not copy it into a worktree.
- Behavior changes follow RED, minimal implementation, then GREEN.
- Before the H2 username-normalization migration mutates rows, preflight every
  legacy username with `UsernamePolicy.normalizeKey`. Invalid values or
  duplicate normalized keys must abort the migration and leave the original
  `username` and `username_normalized` values unchanged.
- Return exact commit SHA, changed paths, commands, and exit codes as delivery
  evidence.
