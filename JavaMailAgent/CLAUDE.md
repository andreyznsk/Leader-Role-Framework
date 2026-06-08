# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

Early skeleton. `src/main/java/org/example/Main.java` is an IntelliJ placeholder — the real implementation follows `JavaMailAgent-RFC.md`. The RFC is the authoritative spec; implement from it.

Target package: `com.mailagent` (not `org.example` — rename as you build out).

## Commands

```bash
# Build fat-jar (run from JavaMailAgent/)
mvn package -q

# Run — MUST be from Leader-Role-Framework/ root (ClaudeRunner needs CLAUDE.md in workDir)
cd ..
java -jar JavaMailAgent/target/mail-agent-1.0.0.jar              # local (Maildev)
APP_ENV=dev MAIL_PASSWORD=secret java -jar JavaMailAgent/target/mail-agent-1.0.0.jar
APP_ENV=prod MAIL_PASSWORD=secret java -jar JavaMailAgent/target/mail-agent-1.0.0.jar
java -jar JavaMailAgent/target/mail-agent-1.0.0.jar --env prod   # same via arg

# Local mail server
docker compose up -d         # starts Maildev
open http://localhost:1080   # web UI

# Send test email to Maildev
curl -s --url "smtp://localhost:1025" \
  --mail-from "sender@test.com" --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Test
From: sender@test.com
To: me@test.com

Body here
EOF

# Tests
mvn test
mvn test -Dtest=MaildevClientTest      # single test class
```

## Architecture

No Spring. Single-threaded background process polling a mailbox.

**Main loop** (`MailAgentJob`): `scheduleWithFixedDelay` — each tick runs sequentially after the previous finishes.
```
listUnread → save to inbox/{id}.json → build prompt → ClaudeRunner → ActionExecutor → markAsRead
```
If ClaudeRunner throws, the email stays in `inbox/` and is retried next tick.

**ClaudeRunner** spawns `claude --print <prompt>` as a subprocess with `workDir = Leader-Role-Framework/`. Expects stdout to be a valid `AgentResponse` JSON. Kills process after `agent.timeout.minutes`.

**ActionExecutor** switches on `AgentResponseType`:
- `REQUEST` → appends `taskLine` to `plans/today.md`, moves email to `processed/`
- `DRAFT` → moves email to `processed/` (draft already written to `drafts/` by Claude)
- `NOISE` → moves email to `processed/`

**MailClient** is an interface with three implementations selected by `mail.protocol` config:
- `maildev` → `MaildevClient` (OkHttp, HTTP API, no auth needed)
- `imap` → `ImapMailClient` (Jakarta Mail)
- `ews` → `EwsMailClient` (EWS Java API, Exchange on-premise)

**Config loading** (`MailConfig.load(env)`):
1. Looks for `application-{env}.properties` next to the JAR
2. Falls back to classpath (for tests)
3. `MAIL_PASSWORD` env var always overrides the file value

## Key Design Constraints

- `Email.body` is truncated to 10,000 chars at read time; `id` is URL-encoded for filenames (EWS IDs contain special chars)
- `markAsRead` is called only after `ActionExecutor` succeeds — ensures at-least-once processing
- Config files with real passwords go in `.gitignore`; only `application-*.properties.example` go to git
- No SMTP / email sending in MVP — `AgentResponseType.SEND` is a future extension
