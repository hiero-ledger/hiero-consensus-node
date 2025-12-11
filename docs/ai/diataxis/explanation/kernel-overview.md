---
title: AI Kernel Overview
status: draft
diataxis: explanation
last-validated: 2025-12-11
---

# Understanding the AI Kernel

The kernel is the smallest viable set of documents and workflows that keep the AI system safe while leaving maximum room for human customization.

## Core Pillars

1. **Guardrails (`docs/ai/system/universal-workflow-constraints.md`)**
   - Non-negotiable behavioral rules for every workflow.
   - Updated centrally whenever the team refines expectations.
2. **Invocation Router (`docs/ai/system/invocations.md`)**
   - Maps natural-language instructions to workflow specs.
   - Ships with only three triggers (show constraints, design workflow, run tutorial) so humans can add their own commands.
3. **Workflow Library (`docs/ai/system/workflows/`)**
   - Initially contains the workflow-design template and the tutorial facilitator.
   - Everything else is created via tutorials or the design workflow.
4. **Learning Layer (`docs/ai/diataxis/`)**
   - Tutorials, how-to guides, references, and explanations for humans.
   - Tutorials double as scripts the agent can facilitate via `run tutorial`.
5. **Task State (`docs/ai/tasks/`)**
   - Persistent task sequences (`active/`, `tabled/`) and templates.
   - Future subdirectories (e.g., discovery logs) live here when workflows call for durable artifacts.

## Extension Flow

1. Run the appropriate tutorial (e.g., guardrail orientation, code-review workflow design, custom SDLC).
2. Capture preferences and requirements during the tutorial.
3. Use `design workflow` to create new specs.
4. Update `docs/ai/system/invocations.md` and Diátaxis docs to reflect the new capability.

## Customization Levers

- Tutorials prompt for human choices (nomenclature, stages, approval gates).
- How-to guides explain how to manage task sequences and other shared artifacts.
- The kernel intentionally excludes advanced workflows such as SDLC rails or bundle management so teams can design their own versions.

Use this overview when orienting new collaborators or deciding where to place new artifacts.
