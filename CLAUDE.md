# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A personal knowledge framework for a Tech Lead entering a new team. It is **not a software project** — there are no builds, tests, or deployments. The content is Markdown documents and Claude Code skills.

## Repository structure

```
workSpace/          # Working materials, filled in as leadership work progresses
  00_people/        # 1:1 meeting plans, checklists, and results per person
skills/             # Claude Code custom skills
  k8s-explorer-skill/   # Skill for exploring Kubernetes clusters via MCP
    SKILL.md            # Skill entrypoint — defines modes and routing logic
    references/
      explore.md        # Step-by-step guide: explore a single namespace → saves <namespace>.md
      summary.md        # Step-by-step guide: read all .md files → generates report.html
```

## Skills

Custom skills live under `skills/`. Each skill directory has a `SKILL.md` as the entrypoint (with YAML frontmatter describing `name`, `description`, `compatibility`) and a `references/` folder with detailed execution guides.

**k8s-explorer** requires the `mcp-kubernetes` MCP server. It operates in two modes:
- `explore <namespace>` — connects to a K8s cluster and saves a structured `<namespace>.md`
- `summary <folder>` — reads all `.md` reports and generates a self-contained `report.html`

When adding a new skill, follow the same structure: `SKILL.md` entrypoint + `references/` for detailed sub-steps.

## Content conventions

- Documents are in Russian (the working language of the team).
- Meeting results and per-person observations go into `workSpace/00_people/`.
- `results.md` is a blank checklist template — copy it per person and fill in after each 1:1.
