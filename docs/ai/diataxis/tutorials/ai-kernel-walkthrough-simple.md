---
title: AI Kernel Walkthrough
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: simple
duration: 20m
---

## Purpose

Introduce the entire AI kernel: directories, guardrails, workflows, tutorials, and task sequences.

## Prerequisites

- [ ] Repository cloned locally.
- [ ] `AGENTS.md` opened in an editor.

## Steps

1. **High-Level Map**
   - Review `docs/ai/README.md` to understand the top-level split (`diataxis/`, `system/`, `tasks/`).
   - Summarize each directory in your own words.
2. **Guardrails & Invocation**
   - Read `docs/ai/system/universal-workflow-constraints.md` and `docs/ai/system/invocations.md`.
   - Note the three kernel triggers and when to run each.
3. **Workflow Library**
   - Open `docs/ai/system/workflows/` and skim the available specs (`ai-workflow-design-workflow`, `ai-workflow-run-tutorial`).
   - Confirm you know where new workflows will be added.
4. **Diátaxis Tour**
   - Browse each subdirectory under `docs/ai/diataxis/`.
   - Identify where explanations, how-tos, references, and tutorials live.
5. **Task Sequences**
   - Read `docs/ai/tasks/README.md` and the template in `docs/ai/tasks/template-sequence.md`.
   - Decide on a slug you would use for your first task sequence.
6. **Next Steps**
   - Pick a follow-up tutorial (e.g., `guardrail-orientation-simple`, `gradle-conventions-lab-medium`).
   - If questions remain, note them in a task sequence or project README so the agent can follow up.

## Reflection

- Which directory do you expect to customize first?
- Are there guardrail or workflow ideas you want to document?
- Which tutorial will you run next?
