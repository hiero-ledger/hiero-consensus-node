---
title: Design Your SDLC Workflow Suite
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: advanced
duration: 90m
---

## Purpose

Co-create a personalized software development life cycle (SDLC) with the human collaborator, capturing their stage names, artifacts, and approval gates before generating workflows.

## Prerequisites

- [ ] Guardrail Orientation tutorial completed.
- [ ] At least one feature or project in mind.
- [ ] Familiarity with `design workflow` and basic invocation editing.

## Steps

1. **Elicit Stage Vocabulary**
   - Ask the human to describe their preferred lifecycle stages (e.g., Discovery, Shaping, Implementation, Delivery).
   - Capture desired entry/exit criteria for each stage.
2. **Identify Artifacts Per Stage**
   - Determine which files or directories should store artifacts (e.g., `/docs/dev/<project>/discovery/`, `/docs/ai/tasks/<project>/issues/`).
   - Decide which stages require durable artifacts vs. ephemeral notes.
3. **Define Automations & Checks**
   - For each stage, ask what the AI should automate (interviews, plan drafting, validation, delivery) and what must remain manual.
   - List required clarifications, preconditions, and confirmation prompts for each workflow.
4. **Sequence Dependencies**
   - Map how stages feed into each other and what artifacts must exist before advancing.
   - Decide how to represent resumable work (task sequences, issue trackers, etc.).
5. **Plan Directory Layout**
   - Confirm where SDLC artifacts will live (suggest using `/docs/dev/` for human-owned docs and `/docs/ai/tasks/` for AI-managed state).
6. **Scaffold Workflows**
   - For each stage, run `design workflow` to create a spec.
   - Add invocation entries with appropriate triggers, aliases, required clarifications, mode, preconditions, confirmation requirements, and outputs; brainstorm distinct aliases and check for collisions.
7. **Verify Invocation Registration**
   - Open `docs/ai/system/invocations.md` to confirm each new row is present and accurate (triggers/aliases, mode, clarifications, confirmation flag, outputs).
   - Refine or add aliases if any are ambiguous or overlapping, then re-verify the table.
8. **Create Supporting Tutorials/How-Tos**
   - Document how humans should engage with each stage (e.g., "How to run the Discovery workflow").
9. **Pilot & Iterate**
   - Optionally run a dry run on a small issue to validate stage transitions.
   - Capture improvements in a task sequence or retrospective doc.

## Reflection

- Do the new stage names align with the team's language?
- Are there additional artifacts or directories to create in `/docs/dev/` or `/docs/ai/tasks/`?
- What is the next tutorial or workflow to run after this design session?
