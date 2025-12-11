---
title: Design a Code Review Workflow
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: medium
duration: 45m
---

## Purpose

Guide the human through defining review dimensions, findings artifacts, and approval flows before creating a bespoke code review workflow.

## Prerequisites

- [ ] Guardrail Orientation tutorial completed (or guardrails reviewed).
- [ ] Human has a list of review concerns (e.g., performance, security).
- [ ] `docs/ai/system/workflows/ai-workflow-design-workflow.md` reviewed.

## Steps

1. **Capture Review Goals**
   - Interview the human about what they want from AI-assisted code reviews (duration, depth, acceptable automation).
   - Document desired review dimensions (e.g., maintainability, security-hardening, documentation alignment).
2. **Define Findings Artifacts**
   - Decide where findings should live (ephemeral markdown, task sequences, issue tracker).
   - Clarify metadata needed per finding (severity, file path, recommended fix, approval checkboxes).
3. **Establish Approval Flow**
   - Determine how the human approves fixes (checkbox editing, explicit chat commands, etc.).
   - Decide whether automatic fixes are allowed and which review types can trigger them.
4. **Map Invocations**
   - Choose trigger phrases and aliases (e.g., `review code`, `review security`); brainstorm distinct, unambiguous aliases and check for collisions.
   - Decide whether separate triggers are needed per review dimension.
5. **Outline Workflow Steps**
   - Draft the main phases (selection, analysis, findings report, approval gate, fix execution, summary).
   - Note required clarifications, preconditions, and confirmation prompts.
6. **Create Workflow Spec**
   - Use `design workflow` to scaffold the new workflow in `docs/ai/system/workflows/`.
   - Add a new row to `docs/ai/system/invocations.md` with trigger, aliases, mode, required clarifications, preconditions, confirmation requirement, and outputs; check for duplicates before saving.
7. **Verify Invocation Registration**
   - Open `docs/ai/system/invocations.md` to confirm the new row is present and accurate (trigger/aliases, mode, clarifications, confirmation flag, outputs).
   - If any aliases feel weak or overlapping, refine them now and re-verify the table.
8. **Document the Process**
   - Add a how-to or tutorial describing how to use the new review workflow.
   - Consider adding a task sequence template for manual findings follow-up.
9. **Pilot & Iterate**
   - Optionally run a dry run on a small issue to validate stage transitions.
   - Capture improvements in a task sequence or retrospective doc.

## Reflection

- Which review dimensions are in scope now, and which are deferred?
- What artifacts or directories were created (e.g., findings storage)?
- Are there outstanding actions (e.g., add tutorial, update invocations) tracked in a task sequence?
