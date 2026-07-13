---
name: cr-mem-034-related-task-dropdowns
description: CR-MEM-034 (RELATES_TO dropdown UI on /ui/today and /ui/tasks/{id}/edit) verified PASS 2026-07-10 via test_e2e/25_task_links.md steps 8-11
metadata:
  type: project
---

CR-MEM-034 adds a collapsible "Связанные задачи" (`<details>`) dropdown showing RELATES_TO
links: `data-testid="today-related-tasks"` on `/ui/today`, and `data-testid="edit-related-tasks"`
inside the "Linked tasks" block on `/ui/tasks/{id}/edit`. Both sides of a RELATES_TO link render
a clickable `<a class="related-task-link" href="/ui/tasks/{other}/edit">{other title}</a>` —
mirrored correctly on both the source and target task's edit page.

Verified 2026-07-10 on branch `feature/MEM-034-2026-07-08`: all 11 steps of
`JavaMemoryService/test_e2e/25_task_links.md` passed, including new steps 8-11 covering this CR.
Touched files were uncommitted at verification time: `TaskLinkService.java`,
`TaskEditController.java`, `TodayViewController.java`, `task-edit.html`, `today.html`.

**Why noting:** first E2E confirmation of this CR's UI surface: dropdown testids, mirrored
link rendering, and correct anchor hrefs all matched expected exactly on first run after a
fresh build — no bugs found. Relevant per [[check-jar-freshness-before-test]]: JAR was 4 days
stale (built 07-06) vs these uncommitted 07-10 source changes; had to rebuild+restart before
testing or the new templates/controllers would not have been exercised at all.

**How to apply:** if CR-MEM-034 is revisited, this scenario file is the regression check.
Before marking the CR "Implemented" in `docs/cr/2026-07-08_CR-MEM-034-related-task-dropdowns.md`
and `docs/cr/REGISTRY.md`, confirm this E2E pass is still current (working tree may have moved
on since this note).
