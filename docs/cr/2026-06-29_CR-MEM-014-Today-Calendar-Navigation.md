# 2026-06-29_CR-MEM-014: Today UI Calendar Navigation

**Дата:** 2026-06-29  
**Статус:** Draft  
**Сервис:** MEM / JavaMemoryService  
**ID:** CR-MEM-014

## Summary

Replace the current **Deadline** filter on `/ui/today` with a
calendar-based navigation panel.

## Motivation

The current deadline filter does not provide a quick overview of
workload by date. A compact calendar allows users to immediately
understand where deadlines are concentrated and filter tasks with one
click.

## Functional Requirements

### Remove

-   Remove the existing **Deadline** filter from the top toolbar.

### Add right sidebar

Add a compact calendar to the right side of the Today page.

Each day displays indicators representing the number of tasks having
that deadline.

Suggested colors:

-   🟢 1 task
-   🟡 2--3 tasks
-   🔴 4+ tasks

### Date filtering

Clicking a calendar day filters the task list to that exact date.

### Quick presets

Below the calendar add:

-   Today
-   Tomorrow
-   This Week

Selecting a preset applies the corresponding filter.

### Reset

Add a **Reset** action that clears every active filter and restores the
complete task list.

## Acceptance Criteria

-   Deadline filter removed.
-   Calendar displayed on the right.
-   Calendar shows deadline indicators.
-   Clicking a date filters tasks.
-   Today/Tomorrow/This Week presets work.
-   Reset restores full task list.
-   Existing task operations continue to work without changes.

## Notes

This change is UX-only. No business logic for tasks should change. The
calendar becomes the single navigation component for deadline filtering.
