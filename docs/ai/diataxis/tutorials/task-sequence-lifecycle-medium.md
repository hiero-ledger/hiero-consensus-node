---
title: Task Sequence Lifecycle
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: medium
duration: 30m
---

## Purpose

Practice creating, updating, and completing AI task sequences under `docs/ai/tasks/` so multi-step work stays resumable and auditable.

## Prerequisites

- [ ] `AGENTS.md` startup protocol completed.
- [ ] Read `docs/ai/tasks/README.md` and `docs/ai/diataxis/how-to-guides/task-sequences.md`.
- [ ] Clean working tree or user-approved dirty state.

## Steps

1. **Plan the Effort**
   - Pick a hypothetical multi-step task (e.g., “document kernel tweaks”).
   - Decide on a short slug (≤5 words, kebab-case) for the sequence.
2. **Create the Sequence**
   - Copy `docs/ai/tasks/template-sequence.md` to `docs/ai/tasks/active/seq-YYYYMMDD-<slug>.md`.
   - Update metadata (title, branch, purpose, related paths) and capture initial context.
3. **Customize the Checklist**
   - Replace template tasks with the actual steps you expect to take.
   - Mention which workflows or tutorials you’ll run under `## Resumption Context`.
4. **Record Decisions & Risks**
   - Add at least one entry to the `## Decisions` table (use ISO timestamps).
   - Document a plausible risk and mitigation.
5. **Simulate Progress**
   - Mark a couple of tasks as complete, update `last-updated`, and append notes describing what happened.
   - Pretend you paused the work; confirm you can identify the next task at a glance.
6. **Complete & Archive**
   - Mark all tasks `[x]`, set `status: completed`, and insert a closing summary under `## Context`.
   - Move the file to `docs/ai/tasks/tabled/` after confirming with the facilitator.
7. **Review Cleanup Workflow**
   - Discuss how the `audit documentation` (or future task-management workflow) will delete completed sequences from `active/`.

## Reflection

- Did the checklist capture enough detail to resume later?
- What naming patterns or extra sections would help your team?
- Should any tutorials or workflows reference this new sequence for follow-up work?
