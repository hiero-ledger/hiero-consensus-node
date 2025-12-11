---
title: AI Task Artifacts
status: draft
diataxis: reference
last-validated: 2025-12-11
---

# AI Task Artifacts

`docs/ai/tasks/` hosts persistent or semi-persistent artifacts created by AI workflows.

## Layout

- `template-sequence.md` – Copy when starting a new task sequence.
- `active/` – Live task sequences that track ongoing work.
- `tabled/` – Completed sequences that are archived for reference.
- Additional subdirectories may be added by new workflows (e.g., `/docs/ai/tasks/<project>/discovery/`).

## Conventions

1. Task sequences MUST be committed to source control for resumability.
2. Ephemeral artifacts (e.g., code review findings) should live outside this tree (typically ignored directories) unless a tutorial instructs otherwise.
3. Keep diffs small: update only checkboxes, timestamps, or append new notes/decisions.
4. Use UTC timestamps in ISO8601 when recording decisions.
5. Archive or delete completed sequences promptly to keep `active/` lean.

Refer to `docs/ai/diataxis/how-to-guides/task-sequences.md` for detailed instructions.
