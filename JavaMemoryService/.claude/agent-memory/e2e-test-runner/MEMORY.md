# Memory Index

- [Check JAR freshness before testing](feedback_check_jar_freshness.md) — health check green != running latest build; verify JAR mtime vs source changes, restart if stale
- [Toast UI editor-all.min.js vendor bug (CR-MEM-029) — RESOLVED](project_toastui_editor_all_vendor_bug.md) — re-vendored 2026-07-03; hideModeSwitch config verified 3x same day, now has a direct assertion, still passes
- [Flyway V20 collision blocked CR-MEM-031 — RESOLVED 2026-07-06](project_flyway_v20_collision_2026-07-03.md) — renumbering V20/V21/V22 fixed it; fresh restart validates clean, full scenario passed
- [zsh echo + jq control-char gotcha](feedback_zsh_echo_jq_control_chars.md) — `echo "$VAR" | jq` mangles escaped \n in JSON strings under zsh; use file-based jq instead
- [CR-MEM-034 related-task dropdowns — PASS 2026-07-10](project_cr_mem_034_related_task_dropdowns.md) — 25_task_links.md steps 8-11 verified today/edit dropdown UI, all mirrored correctly
