---
title: AI Kernel System Overview
status: draft
diataxis: reference
last-validated: 2025-12-11
---

# AI Kernel System Overview

Purpose: describe the minimal AI system layout, the responsibilities of each directory, and how humans can extend it.

## Directory Map

- `docs/ai/system/`
  - `README.md` (this file): kernel orientation.
  - `universal-workflow-constraints.md`: mandatory guardrails.
  - `invocations.md`: trigger→workflow routing table.
  - `workflows/`: canonical workflow specs (initially the workflow design workflow and tutorial facilitator).
  - `knowledge/` (optional future home for ontologies or service maps).
- `docs/ai/diataxis/`
  - Tutorials, how-to guides, references, and explanations oriented toward humans customizing the AI system.
  - Tutorial files double as scripts that the `run tutorial` workflow can facilitate interactively.
- `docs/ai/tasks/`
  - `active/`: committed task sequences for resumable work.
  - `tabled/`: archived sequences worth preserving.
  - Additional subdirectories (e.g., discovery logs) may be added per workflow agreements.
- `docs/diataxis/`
  - Repository-wide documentation outside of the AI kernel.
- `docs/dev/`
  - Project or issue specific notes written by humans (optional inputs to AI workflows).

## Extension Philosophy

The kernel ships only the minimum workflows needed to stay safe (`show ai constraints`), scaffold new workflows (`design workflow`), and guide humans through tutorials (`run tutorial`). Everything else—SDLC stages, audits, reviews—is expected to be recreated (or redesigned) by humans via tutorials or bespoke workflow design sessions.

Key expectations:
1. Prefer co-design: use the tutorials or the design workflow to define your own stages, terminology, and approval gates.
2. Keep guardrails intact: derive new workflows from the provided template and reference the universal constraints.
3. Document intent via Diátaxis: when you extend the system, add matching explanation/how-to/tutorial entries so others can learn your conventions.

## Tutorial-Facilitated Growth

The `run tutorial` workflow lets the AI agent facilitate lessons stored under `docs/ai/diataxis/tutorials/`. Tutorials include metadata (title, duration, complexity) and structured steps; the agent reads them, checks prerequisites, and collaborates with the human to complete each step. Use tutorials to:
- Review and customize guardrails.
- Design focused workflows (e.g., code review modes).
- Define personalized SDLC stages.

## References

- Guardrails: `docs/ai/system/universal-workflow-constraints.md`
- Invocation Index: `docs/ai/system/invocations.md`
- Workflow Template: `docs/ai/system/workflows/ai-workflow-design-workflow.md`
- Tutorial Catalog: `docs/ai/diataxis/tutorials/`
