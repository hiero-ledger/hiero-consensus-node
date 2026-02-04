---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# CLPR documentation

This directory is the source of truth for CLPR design, requirements, decisions, and current implementation state.

## Structure

- overview/ : Target state, expository, stable descriptions of CLPR and its components.
- overview/glossary.md : Shared terminology and definitions.
- requirements/ : Clustered requirements by component/capability.
- requirements/traceability.md : Requirement-to-test mapping.
- adrs/ : Architecture decision records (dated).
- implementation/ : Current behavior, active work, and API details.
- implementation/iteration-plan.md : Iteration milestones and minimum tests.
- implementation/conformance.md : Prototype/Java parity expectations.
- status.md : Short, human-readable current priorities.
- todo-backlog.md : Untriaged work items and logistical follow-ups.

## Conventions

- Keep overview/ free of volatile details (e.g., evolving message formats).
- Keep implementation/ aligned with the current codebase and active work.
- Use short metadata preambles for AI/human parsing.
- Add ADRs when a decision changes direction or affects interfaces.
