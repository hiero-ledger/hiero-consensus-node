---
title: Design a Custom Kernel
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: advanced
duration: 90m
---

## Purpose

Guide the human through replacing the default kernel ontology (workflows, invocations, guardrails) with their own terminology and interaction model.

## Prerequisites

- [ ] Completed `ai-kernel-walkthrough-simple` tutorial (understands current kernel).
- [ ] Desire to introduce new terms/structures (e.g., “playbooks” instead of workflows).
- [ ] Familiarity with Markdown and directory layout conventions.

## Steps

1. **Capture Desired Vocabulary**
   - Interview the human about how they prefer to describe AI interactions (e.g., “missions”, “recipes”, “rules of engagement”).
   - List the terms they want for: guardrails, command routing, executable definitions, tutorials, and task tracking.
2. **Map Old → New Concepts**
   - Create a table translating current kernel components (guardrails, invocations, workflows, tutorials, tasks) to the new terms.
   - Decide which files/directories need renaming vs. which should be replaced entirely.
3. **Plan Directory Layout**
   - Sketch the desired directory tree (e.g., `docs/ai/system` → `docs/ai/<new-term>`, `workflows/` → `playbooks/`).
   - Decide where ontology docs (explanations of the new terms) will live.
4. **Define Replacement Guardrails**
   - Draft the new guardrail document (even if terminology changes) to ensure safety expectations remain explicit.
   - Capture how the startup protocol and task-sequence requirements will translate.
5. **Outline Command Routing**
   - Decide how the current invocation table will be represented (e.g., rename to “mission index”).
   - Identify the minimum triggers the new kernel will ship with and brainstorm distinct, unambiguous aliases; check for collisions.
6. **Plan Migration Steps**
   - If keeping history, decide whether to move existing specs into backup directories (e.g., `/backup/kernel-default/`).
   - List the files to delete vs. adapt; consider writing a task sequence to track the migration.
7. **Scaffold New Kernel Files**
   - Create placeholder READMEs and minimal specs using the new terminology.
   - Update `AGENTS.md` (or its replacement) to point at the new ingestion order and startup protocol.
   - Update the invocation index (or its renamed equivalent) with triggers, aliases, modes, required clarifications, preconditions, confirmation requirements, and outputs; ensure uniqueness before saving.
8. **Verify Invocation Registration**
   - Open the invocation index to confirm new rows are present and accurate (triggers/aliases, mode, clarifications, confirmation flag, outputs).
   - Refine aliases if any are ambiguous or overlapping, then re-verify.
9. **Document the Ontology**
   - Write an explanation doc describing the new concept set and how future collaborators should extend it.
   - Update tutorials (or create new ones) that teach the new structure.
10. **Review & Iterate**
   - Summarize the planned changes back to the human.
   - Decide whether to proceed immediately or stage the work via task sequences.

## Reflection

- What new terms or structures did you introduce?
- How will collaborators learn the new ontology (tutorials, explanations)?
- Do you need additional workflows or automation to support the custom kernel?
