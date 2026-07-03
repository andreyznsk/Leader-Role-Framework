---
name: check-jar-freshness-before-test
description: Always verify the running JavaMemoryService JAR was built after the latest source/resource changes before trusting a "service already running" state
metadata:
  type: feedback
---

Before running E2E/Playwright tests against an already-running JavaMemoryService instance, compare the JAR mtime (`stat target/memory-service.jar`) against the mtime of recently-changed source/resource files (`git status --porcelain` + `git log -1 --format=%ci -- <file>`). Also smoke-check any newly-added static assets directly (e.g. `curl -o /dev/null -w '%{http_code}' http://localhost:8082/vendor/...`) rather than assuming they're served just because the port answers `/actuator/health` 200.

**Why:** During CR-MEM-029 (Toast UI editor) verification, JavaMemoryService was already up on :8082 and healthy, but the JAR had been built *before* the CR's template/vendor-asset changes landed on disk. The vendor JS/CSS returned 404 even though health check was green — health check only proves the process is alive, not that it reflects current source.

**How to apply:** Whenever asked to run tests after a CR/code change, run `./test-runner/build.sh --service <X>` then `./test-runner/stop-services.sh --service <X>` + `./test-runner/start-services.sh --service <X>` (start-services.sh silently no-ops if a PID file shows the process already running — it will NOT pick up a fresh build on its own, must stop first). Then re-verify health + any new static endpoints before running the actual test spec. See [[toastui-editor-all-vendor-bug]] for a concrete case this caught.
