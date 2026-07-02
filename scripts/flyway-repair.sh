#!/usr/bin/env bash
set -Eeuo pipefail

# flyway-repair.sh
# -----------------
# Realigns Flyway's `flyway_schema_history` checksums in a target database
# with the migration files/classes currently in JavaMemoryService (both the
# .sql files under src/main/resources/db/migration and the Java-based
# migrations under src/main/java/db/migration, e.g. V13/V14/V15/V19).
#
# Runs via `mvn exec:java` (ru.andreyz.memoryservice.tools.FlywayAdminTool)
# instead of the standalone flyway-maven-plugin/CLI. That matters: the
# standalone plugin only has the JDBC driver on its classpath, not the
# module's own compiled classes, so it cannot see the Java-based migrations.
# It reports them as "Missing", and `flyway:repair` run that way will DELETE
# their flyway_schema_history rows — which then breaks the real app on next
# boot ("Detected resolved migration not applied to database"). exec:java
# runs with the module's full compile classpath, so it resolves everything
# the same way the real Spring Boot app does.
#
# When to use `repair`:
#   You edited the CONTENT of an already-applied migration file WITHOUT
#   changing what it actually does to the schema (e.g. reordered
#   `DEFAULT`/`PRIMARY KEY`, split a multi-column ALTER TABLE into
#   single-column statements). Flyway then refuses to start the app with
#   "Migration checksum mismatch" for that version, because the file on disk
#   no longer matches what was recorded when it first ran. `repair`
#   recalculates and rewrites the checksum column only — it does not re-run
#   any SQL. Safe ONLY if the resulting schema is identical to what the old
#   content already produced; do not use it to paper over a real semantic
#   difference.
#
# Usage:
#   export FLYWAY_URL="jdbc:postgresql://<host>:5432/<db>"
#   export FLYWAY_USER="<user>"
#   export FLYWAY_PASSWORD="<password>"
#   ./scripts/flyway-repair.sh            # interactive: info -> confirm -> repair -> info
#   ./scripts/flyway-repair.sh --yes      # skip the confirmation prompt
#
# What it does, in order:
#   1. exec:java info      — shows current state/mismatches, no changes made.
#   2. Prompts for confirmation (unless --yes).
#   3. pg_dump of flyway_schema_history only, saved next to this script, as a
#      cheap rollback point (skipped automatically if pg_dump/psql aren't
#      available or can't reach the DB from this machine — repair still
#      proceeds).
#   4. exec:java repair     — rewrites checksums, runs no migration SQL.
#   5. exec:java info       — confirms everything is now "Success"/up to date.
#
# Requires: FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD in the environment.
# Run from the repository root (paths below assume that).

AUTO_YES=0
for arg in "$@"; do
  case "$arg" in
    --yes|-y) AUTO_YES=1 ;;
    -h|--help)
      sed -n '1,40p' "$0"
      exit 0
      ;;
  esac
done

log() { printf '[flyway-repair] %s\n' "$*"; }
fail() { printf '[flyway-repair] ERROR: %s\n' "$*" >&2; exit 1; }

for var in FLYWAY_URL FLYWAY_USER FLYWAY_PASSWORD; do
  [[ -n "${!var:-}" ]] || fail "Environment variable $var is not set."
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

run_tool() {
  mvn -q -pl JavaMemoryService compile exec:java -Dexec.args="$1"
}

log "Target: ${FLYWAY_URL} (user: ${FLYWAY_USER})"
log "Step 1/4: info (current state, read-only)"
run_tool info

if [[ "$AUTO_YES" -ne 1 ]]; then
  read -r -p "[flyway-repair] Proceed with 'repair' against this database? [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]] || fail "Aborted by user."
fi

BACKUP_FILE="${REPO_ROOT}/scripts/flyway_schema_history_backup_$(date +%Y%m%d_%H%M%S).sql"
log "Step 2/4: best-effort backup of flyway_schema_history -> ${BACKUP_FILE}"
DB_URL_NO_JDBC="${FLYWAY_URL#jdbc:}"
DB_HOST="$(printf '%s' "$DB_URL_NO_JDBC" | sed -E 's#postgresql://([^:/]+).*#\1#')"
DB_PORT="$(printf '%s' "$DB_URL_NO_JDBC" | sed -E 's#postgresql://[^:/]+:?([0-9]*)/.*#\1#')"
DB_NAME="$(printf '%s' "$DB_URL_NO_JDBC" | sed -E 's#postgresql://[^/]+/([^?]+).*#\1#')"
DB_PORT="${DB_PORT:-5432}"
if command -v pg_dump >/dev/null 2>&1; then
  if PGPASSWORD="$FLYWAY_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$FLYWAY_USER" -d "$DB_NAME" \
      -t memory.flyway_schema_history -f "$BACKUP_FILE" 2>/tmp/flyway-repair-pgdump.err; then
    log "Backup saved."
  else
    log "WARNING: pg_dump backup failed, continuing without it (see /tmp/flyway-repair-pgdump.err)."
  fi
else
  log "WARNING: pg_dump not found on PATH, skipping backup step."
fi

log "Step 3/4: repair (rewrites checksums only, runs no migration SQL)"
run_tool repair

log "Step 4/4: info (verify everything is now aligned)"
run_tool info

log "Done. Review the table above — all applied versions should show state 'Success'."
