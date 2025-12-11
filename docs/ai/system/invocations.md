# AI Workflow Invocation Index

Purpose
-------

Single authoritative mapping of natural-language triggers to kernel workflows. This enables predictable invocation, clarifications, and confirmation gates.

Usage Model
-----------

1. User issues a natural-language instruction (e.g., "run tutorial").
2. Agent matches EXACT trigger first; if no exact match, checks aliases (case-insensitive, trimmed).
3. If multiple matches share an alias, agent must present disambiguation to the user.
4. If workflow Mode = Modifies (M), agent MUST produce a dry-run report and request explicit approval before edits.
5. All modifications remain uncommitted unless the user explicitly instructs otherwise.

Modes
-----

- **R** (Read-only): Inspect/report without editing files.
- **M** (Modifies): May edit files after explicit user approval.
- **R→M**: Begins read-only; may transition to modifications after approval.

Invocation Table
----------------

| Trigger (Primary)   | Aliases                   | Workflow Spec                                   | Scope / Intent                                                      | Mode | Required Clarifications                          | Preconditions                         | Confirmation Needed?                   | Outputs                                       |
|---------------------|---------------------------|-------------------------------------------------|---------------------------------------------------------------------|------|-----------------------------------------------|--------------------------------------|----------------------------------------|----------------------------------------------|
| show ai constraints | list guardrails; guardrail summary | `universal-workflow-constraints.md`             | Display universal workflow constraints and discuss adjustments      | R    | None                                          | None                                 | No                                     | Rendered constraints summary                 |
| design workflow     | add ai workflow; create workflow  | `workflows/ai-workflow-design-workflow.md`      | Collaboratively design and register a new workflow specification    | R→M  | Natural-language description of desired workflow | Clean working tree                    | Yes (before creating files)           | Draft workflow file + invocation entry plan  |
| run tutorial        | start tutorial; walkthrough       | `workflows/ai-workflow-run-tutorial.md`         | Facilitate a tutorial from `docs/ai/diataxis/tutorials/` interactively | R→M  | Tutorial slug (if not provided)                 | Tutorial file exists; clean or approved dirty tree | Yes (before applying tutorial-generated edits) | Guided tutorial session notes / optional artifacts |

Adding a New Invocation
-----------------------

1. Ensure a formal workflow spec exists (or create via "design workflow").
2. Choose a concise trigger phrase; ensure uniqueness across triggers and aliases.
3. Specify Mode (R, M, or R→M).
4. Define Required Clarifications – keep them minimal and deterministic.
5. State Preconditions; if not met, agent must ask user to continue or abort.
6. Describe workflow outputs.
7. Update this table with a single-row addition.

Safety & Escalation
-------------------

- Any M or R→M workflow MUST show planned file changes (paths + line counts) before editing and await explicit approval.
- If a requested action implies large-scale changes (>400 LOC across docs), warn & seek confirmation or propose splitting work.
- If workflow prerequisites (e.g., tutorial files) are missing, offer to scaffold them (with separate approval) or abort.
