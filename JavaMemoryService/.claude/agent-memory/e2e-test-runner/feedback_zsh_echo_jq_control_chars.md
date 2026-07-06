---
name: zsh-echo-jq-control-chars
description: zsh builtin `echo` mangles literal backslash-n inside a captured JSON string, causing jq "Invalid string: control characters" — not an app bug
metadata:
  type: feedback
---

When a curl response body contains a JSON string field whose value itself contains an escaped
`\n` (e.g. intake `sourceText` holding pretty-printed JSON as a string), piping it through
`echo "$VAR" | jq ...` in zsh can fail with:

```
jq: parse error: Invalid string: control characters from U+0000 through U+001F must be escaped
```

**Why:** zsh's builtin `echo` interprets backslash escape sequences by default, turning the
literal two-character sequence `\` `n` inside the JSON string into an actual newline byte before
jq ever sees it — producing invalid unescaped-control-char JSON. This is a shell quoting artifact,
not a bug in the service response.

**How to apply:** when a scenario step's `jq` call errors with this message, don't assume the
API is broken. Verify by writing the raw response to a file first (`curl ... > out.json`) and
running `jq` on the file — if that parses cleanly, it confirms the failure was `echo`/zsh escape
mangling, not a real defect. Prefer `curl -s ... > file.json` + `jq ... file.json`, or `printf '%s'
"$VAR"`, over `echo "$VAR" | jq` when the payload may contain escaped control chars. Seen during
CR-MEM-031 (`test_e2e/25_task_links.md`) Step 6 (proposeTaskLink intake) on 2026-07-06 — the
underlying `/api/intake` POST/apply calls were correct (HTTP 201 → NEW → APPLIED) once verified
via file-based jq.
