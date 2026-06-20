#!/usr/bin/env bash
set -Eeuo pipefail

REMOTE_NAME="leaderos-drive"
ARCHITECTURE_FOLDER_ID="1CFsKNXVYpLqtceyNj2-tpoE710x3rrs4"
IDEAS_FOLDER_ID="1cQPlcFSuAXH3FsgRUPi0wh4dDjObuFly"

# The script expects an rclone Google Drive remote named $REMOTE_NAME.
# Folder IDs are applied with --drive-root-folder-id, so files are copied into
# the configured Drive folders without storing OAuth credentials in this repo.

DRY_RUN=0

usage() {
  cat <<USAGE
Usage:
  ./scripts/sync-docs-to-drive.sh [--dry-run]

Options:
  --dry-run   Show what would be uploaded without changing Google Drive.
  -h, --help  Show this help.
USAGE
}

log() {
  printf '[sync-docs] %s\n' "$*"
}

fail() {
  printf '[sync-docs] ERROR: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

command -v rclone >/dev/null 2>&1 || fail "rclone is not installed. See docs/google-drive-sync.md."

if ! rclone listremotes | grep -Fxq "${REMOTE_NAME}:"; then
  fail "rclone remote '${REMOTE_NAME}' is not configured. Run: rclone config"
fi

ensure_auth() {
  local err
  if err="$(rclone about "${REMOTE_NAME}:" 2>&1)"; then
    return 0
  fi
  if echo "$err" | grep -qE "invalid_grant|Token has been expired|revoked"; then
    log "OAuth token expired — запускаю переподключение..."
    rclone config reconnect "${REMOTE_NAME}:"
    log "Токен обновлён. Продолжаю..."
  else
    fail "Не удалось подключиться к remote '${REMOTE_NAME}': $err"
  fi
}

ensure_auth

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"
cd "$REPO_ROOT"

shopt -s nullglob globstar

declare -A ARCHITECTURE_FILES=()
declare -A IDEAS_FILES=()

add_existing_file() {
  local target="$1"
  local file="$2"

  [[ -f "$file" ]] || return 0

  case "$target" in
    architecture) ARCHITECTURE_FILES["$file"]=1 ;;
    ideas) IDEAS_FILES["$file"]=1 ;;
    *) fail "Internal error: unknown target '$target'" ;;
  esac
}

add_glob() {
  local target="$1"
  shift
  local file

  for file in "$@"; do
    add_existing_file "$target" "$file"
  done
}

# Root-level project documents. Keep this list conservative to avoid uploading
# agent prompts, local notes, or unrelated operational files by accident.
ROOT_ARCHITECTURE_DOCS=(
  "AGENT.md"
  "ARCHITECTURE.md"
  "README.md"
  "RFC-test-runner.md"
  "LeaderOS_Daily_Cycle_concept.md"
)

for file in "${ROOT_ARCHITECTURE_DOCS[@]}"; do
  add_existing_file architecture "$file"
done

add_glob architecture \
  JavaMemoryService/RFC/*.md \
  JavaMailAgent/RFC/*.md \
  JavaRagService/RFC/*.md \
  common/RFC/*.md \
  cr/**/*.md \
  JavaMemoryService/cr/**/*.md \
  JavaMailAgent/cr/**/*.md \
  JavaRagService/cr/**/*.md

add_glob ideas ideas/**/*.md

copy_file() {
  local folder_id="$1"
  local files_list="$2"
  local args=(
    copy
    "$REPO_ROOT"
    "${REMOTE_NAME}:"
    --drive-root-folder-id "$folder_id"
    --files-from "$files_list"
    --create-empty-src-dirs=false
    --progress
  )

  if [[ "$DRY_RUN" -eq 1 ]]; then
    args+=(--dry-run)
  fi

  rclone "${args[@]}"
}

copy_group() {
  local name="$1"
  local folder_id="$2"
  shift 2
  local files=("$@")
  local file
  local files_list

  if [[ "${#files[@]}" -eq 0 ]]; then
    log "No files found for ${name}; skipping."
    return 0
  fi

  log "${name}: ${#files[@]} file(s)"
  for file in "${files[@]}"; do
    log "copy ${file} -> ${REMOTE_NAME}:${file}"
  done

  files_list="$(mktemp)"
  printf '%s\n' "${files[@]}" > "$files_list"
  TEMP_FILES+=("$files_list")

  copy_file "$folder_id" "$files_list"
}

TEMP_FILES=()
cleanup() {
  local file
  for file in "${TEMP_FILES[@]}"; do
    [[ -f "$file" ]] && rm -f "$file"
  done
}
trap cleanup EXIT

mapfile -t ARCHITECTURE_LIST < <(printf '%s\n' "${!ARCHITECTURE_FILES[@]}" | sort)
mapfile -t IDEAS_LIST < <(printf '%s\n' "${!IDEAS_FILES[@]}" | sort)

if [[ "$DRY_RUN" -eq 1 ]]; then
  log "Mode: dry-run. No Google Drive files will be changed."
else
  log "Mode: upload. Existing Google Drive files may be updated; nothing will be deleted."
fi

log "Remote: ${REMOTE_NAME}"
log "Architecture folder ID: ${ARCHITECTURE_FOLDER_ID}"
log "Ideas folder ID: ${IDEAS_FOLDER_ID}"

copy_group "architecture docs" "$ARCHITECTURE_FOLDER_ID" "${ARCHITECTURE_LIST[@]}"
copy_group "ideas docs" "$IDEAS_FOLDER_ID" "${IDEAS_LIST[@]}"

log "Done."
