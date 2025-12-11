---
title: Guardrail Orientation Tutorial
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: simple
duration: 15m
---

## Purpose

Review the kernel guardrails and capture any adjustments the human collaborator wants before running other workflows.

## Prerequisites

- [ ] Repository cloned and accessible.
- [ ] `AGENTS.md` read at least once.
- [ ] User available to discuss guardrail preferences.

## Steps

1. **Confirm Shared Context**
   - Read `AGENTS.md` and summarize startup protocol obligations.
   - Ask the human whether anything in the protocol should change for their workflow.
2. **Walk Through Constraints**
   - Open `docs/ai/system/universal-workflow-constraints.md`.
   - Discuss each MUST constraint; capture any that the human wants to tighten or relax.
3. **Discuss Automation Scope**
   - Review the "Prohibited Actions" and "Conditional Constraints" sections.
   - Ask the human if additional safeguards or exceptions are needed.
4. **Record Adjustments**
   - Append notes to the tutorial session summary (or create a task sequence) describing agreed updates.
   - If the human wants to edit the guardrail file immediately, confirm and make the change via the design workflow or direct edit (with approval).
5. **Plan Follow-Up Tutorials**
   - Suggest next tutorials (e.g., `medium-design-code-review-workflow`, `advanced-design-sdlc`) based on the conversation.

## Reflection

- What guardrail adjustments were requested?
- Are there follow-up tasks to codify those changes?
- Which tutorial will you run next?
