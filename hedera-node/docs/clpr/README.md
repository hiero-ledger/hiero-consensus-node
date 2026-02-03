---
scope: clpr
audience: engineering
status: draft
last_updated: 2026-01-28
---

# CLPR documentation

This directory is the source of truth for CLPR design, requirements, decisions, and current implementation state.

## Structure

- overview/ : Target state, expository, stable descriptions of CLPR and its components.
- requirements/ : Clustered requirements by component/capability.
- adrs/ : Architecture decision records (dated).
- implementation/ : Current behavior, active work, and API details.
- status.md : Short, human-readable current priorities.
- todo-backlog.md : Untriaged work items and logistical follow-ups.

## Conventions

- Keep overview/ free of volatile details (e.g., evolving message formats).
- Keep implementation/ aligned with the current codebase and active work.
- Use short metadata preambles for AI/human parsing.
- Add ADRs when a decision changes direction or affects interfaces.

