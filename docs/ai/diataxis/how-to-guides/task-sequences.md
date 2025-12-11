---
title: Managing AI Task Sequences
status: draft
diataxis: how-to-guide
last-validated: 2025-12-11
---

# How-To: Manage AI Task Sequences

Task sequences capture multi-step AI work that may span several interactions. They live under `docs/ai/tasks/`.

## When to Create One

- The upcoming work has more than ~4 steps or requires back-and-forth approvals.
- The task spans multiple files or domains (e.g., code + docs).
- You expect to pause/resume later or hand work to someone else.

## Directory Structure

```
docs/ai/tasks/
  README.md               ← overview + conventions
  template-sequence.md    ← copy when creating a new sequence
  active/                 ← live sequences (committed)
  tabled/                 ← archived sequences worth keeping
```

## Steps

1. **Copy the template**
   - `cp docs/ai/tasks/template-sequence.md docs/ai/tasks/active/seq-YYYYMMDD-your-slug.md`
2. **Fill metadata**
   - Update `title`, `status`, `created`, `last-updated`, `branch`, and `purpose`.
3. **Add checklist items**
   - Include only the pending subset of tasks relevant to your workflow run.
4. **Record context & decisions**
   - Append short notes when scope changes or decisions are made.
5. **Update responsibly**
   - Mark checkboxes, refresh `last-updated`, and append new notes—avoid rewriting history.
6. **Complete & archive**
   - Once all boxes are checked: set `status: completed`, summarize in `## Context`, then ask the user whether to delete or move to `tabled/`.

## Tips

- Keep slugs short (≤5 words in kebab-case).
- When referencing files, use workspace-relative paths for clarity.
- If you discover new work that deserves its own sequence, note it under `## Decisions` and start another file rather than overloading the current one.
- Remember to scan `docs/ai/tasks/active/` at session start (per `AGENTS.md`).
