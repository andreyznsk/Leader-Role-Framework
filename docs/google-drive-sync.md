# Google Drive documentation sync

This project can upload LeaderOS markdown documentation to Google Drive with `rclone`.

The sync script uses `rclone copy`, not `rclone sync`, so it uploads and updates files but does not delete anything from Google Drive.

## 1. Install rclone on Ubuntu

```bash
sudo apt update
sudo apt install rclone
```

Check the installation:

```bash
rclone version
```

## 2. Create the rclone remote

Run:

```bash
rclone config
```

Choose:

```text
n) New remote
name> leaderos-drive
Storage> drive
```

Then follow the Google Drive OAuth flow in the browser. Do not commit any generated rclone config, cache, OAuth tokens, or credentials to the repository.

The script expects this remote name:

```text
leaderos-drive
```

You can verify it with:

```bash
rclone listremotes
```

Expected output includes:

```text
leaderos-drive:
```

## 3. Google Drive folder IDs

The script keeps folder IDs in variables at the top of `scripts/sync-docs-to-drive.sh`:

```bash
REMOTE_NAME="leaderos-drive"
ARCHITECTURE_FOLDER_ID="1CFsKNXVYpLqtceyNj2-tpoE710x3rrs4"
IDEAS_FOLDER_ID="1cQPlcFSuAXH3FsgRUPi0wh4dDjObuFly"
```

`rclone` receives these IDs through `--drive-root-folder-id`, so the same remote can copy files into the specific Google Drive folders.

Current folders:

- architecture: `1CFsKNXVYpLqtceyNj2-tpoE710x3rrs4`
- ideas: `1cQPlcFSuAXH3FsgRUPi0wh4dDjObuFly`

## 4. Run a dry-run

From the repository root:

```bash
./scripts/sync-docs-to-drive.sh --dry-run
```

This prints the files that would be copied and lets `rclone` show planned uploads without changing Google Drive.

## 5. Run the real upload

From the repository root:

```bash
./scripts/sync-docs-to-drive.sh
```

The script copies:

- `ARCHITECTURE.md`
- `AGENT.md`
- selected root project docs such as `README.md`, `RFC-test-runner.md`, and `LeaderOS_Daily_Cycle_concept.md` when present
- `JavaMemoryService/RFC/*.md`
- `JavaMailAgent/RFC/*.md`
- `JavaRagService/RFC/*.md`
- `common/RFC/*.md`
- `cr/**/*.md`
- service CR files under `JavaMemoryService/cr`, `JavaMailAgent/cr`, and `JavaRagService/cr`
- `ideas/**/*.md` when the `ideas` directory exists

Architecture, RFC, and CR files go to the architecture folder. Ideas files go to the ideas folder.

## 6. IntelliJ IDEA External Tool

Open:

```text
Settings -> Tools -> External Tools -> +
```

Use these fields:

```text
Name: Sync LeaderOS docs to Google Drive
Program: /bin/bash
Arguments: scripts/sync-docs-to-drive.sh
Working directory: $ProjectFileDir$
```

Optional dry-run tool:

```text
Name: Dry-run LeaderOS docs Google Drive sync
Program: /bin/bash
Arguments: scripts/sync-docs-to-drive.sh --dry-run
Working directory: $ProjectFileDir$
```

## 7. Safety notes

- The script uses `rclone copy`, not `rclone sync`.
- The script does not delete files from Google Drive.
- The script fails if `rclone` is missing.
- The script fails if the `leaderos-drive` remote is not configured.
- OAuth credentials stay in the user's rclone config, normally under `~/.config/rclone/`, outside this repository.
