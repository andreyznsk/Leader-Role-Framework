---
name: flyway-v20-collision-blocked-cr-mem-031
description: Shared dev Postgres had a stray flyway_schema_history row for version 20 from an unknown/uncommitted source, blocking JavaMemoryService startup during CR-MEM-031 (task links) E2E testing — RESOLVED 2026-07-06 via renumbering
metadata:
  type: project
---

## RESOLVED 2026-07-06

Working tree renumbered the colliding migrations: `V20__task_status_delegation_labels.sql` →
`V21__task_status_delegation_labels.sql`, and task_links moved from `V21` to
`V22__task_links.java`. Rebuilt (`./test-runner/build.sh --service JavaMemoryService`) and did a
fresh restart (`./test-runner/start-services.sh --service JavaMemoryService`) — Flyway log now
reads:

```
o.f.core.internal.command.DbValidate  : Successfully validated 25 migrations (execution time 00:00.017s)
o.f.core.internal.command.DbMigrate   : Current version of schema "memory": 22
o.f.core.internal.command.DbMigrate   : Schema "memory" is up to date. No migration necessary.
```

`flyway_schema_history` now shows a clean, non-colliding sequence: rank 26=v20 (task attachments),
rank 27=v21 (task status delegation labels), rank 28=v22 (task links, installed 2026-07-06
10:21:19). No more "Detected applied migration not resolved locally" error. Full E2E scenario
`JavaMemoryService/test_e2e/25_task_links.md` (CR-MEM-031) ran end-to-end afterward, all 7 steps
PASS.

**Why this matters going forward:** confirms the fix was the version renumbering, not some
transient DB state — a fresh JVM restart today validated cleanly against the shared
`leader-postgres`/`leader_framework` DB. If a *future* CR picks a Flyway version number that
another concurrent session also picks, expect the same class of collision — always check
`flyway_schema_history` first (see original incident notes below) before touching migration files.

On 2026-07-03, while running E2E tests for CR-MEM-031 (task links), rebuilding and restarting
JavaMemoryService (per [[check-jar-freshness-before-test]]) caused Flyway validation to fail on
startup:

```
Detected applied migration not resolved locally: 20.
```

Root cause found in `memory.flyway_schema_history` (queried via
`docker exec leader-postgres psql -U superuser -d leader_framework`):

| installed_rank | version | description | type | installed_by | installed_on |
|---|---|---|---|---|---|
| 26 | 20 | task attachments | JDBC | memory_user | 2026-07-03 12:20:32 |
| 27 | 20 | task status delegation labels | SQL | memory_user | 2026-07-03 15:54:10 |

Rank 27 (`V21__task_status_delegation_labels.sql`) has **no matching file anywhere** in this repo
(working tree or `git log --all`) — it was applied directly against the shared dev Postgres
(`leader-postgres` container, db `leader_framework`) by something outside this working copy,
most likely a different concurrent session/worktree using its own `V20` for an unrelated feature
("task status delegation labels") that collided with this repo's legitimate `V20__task_attachments`.//
Timing (15:54:10) falls right in the middle of the CR-MEM-031 work window, so it was live and
concurrent, not stale history.

**Effect:** any fresh JVM start of JavaMemoryService now fails Flyway validation and won't come up
at all, regardless of whether the CR-MEM-031 code itself is correct. The *previously running* JVM
stayed healthy because Flyway only validates at startup — the conflicting row was inserted after
that JVM had already passed validation, so healthcheck stayed green until an actual restart.

**Why this matters going forward:** this Postgres instance (`leader-postgres`, db
`leader_framework`, schema `memory`) is shared across sessions/worktrees. Two independent efforts
picking the same next Flyway version number will collide in the shared history table even if
their code never conflicts in git. Next time a fresh-JVM restart fails with "Detected applied
migration not resolved locally: N", check `flyway_schema_history` for a duplicate/unexplained row
at that version before assuming the current CR's migration is broken.

**What I did NOT do:** did not run `flyway repair`, did not delete the stray history row, did not
touch any file under `*/db/migration` — CLAUDE.md explicitly forbids changing migrations, and
deleting another session's schema_history row without coordination risks destroying their
in-flight work. Left JavaMemoryService down and reported the blocker instead of self-fixing.

**How to apply:** when a restart-after-rebuild fails validation, always query
`flyway_schema_history` directly (`docker exec leader-postgres psql -U superuser -d
leader_framework -c "SELECT installed_rank, version, description, type, installed_by, installed_on
FROM memory.flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"`) before concluding the
CR's own migration is at fault. If a stray/duplicate version shows up with no matching file in the
repo, it's cross-session drift on shared infra, not a product bug — surface it to the user rather
than repairing it yourself.
