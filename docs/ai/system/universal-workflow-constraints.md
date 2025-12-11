---
title: Universal Workflow Constraints
status: active
diataxis: reference
audience: [AI agents, Human contributors]
last-validated: 2025-12-11
---

# Universal Workflow Constraints

Purpose
-------

Define a single authoritative set of guardrails that every AI workflow MUST honor. Individual workflows may add stricter rules but MUST NOT weaken or contradict these constraints.

Applicability
-------------

Applies to all AI agent activities (code generation, refactors, documentation updates, test authoring, analysis) unless a maintainer explicitly grants a temporary exception via a tracked issue or PR discussion.

Core Constraints (MUST)
-----------------------

1. **No Unauthorized Commits or Pushes**
   - AI agents MUST NOT create commits or attempt to push to any remote without explicit, current-session user instruction.
   - All changes remain as uncommitted working tree modifications unless the user directs otherwise.
2. **Read-First Discipline**
   - Inspect relevant files and context before proposing or applying edits. Avoid speculative edits.
3. **Minimal, Cohesive Diffs**
   - Restrict changes to the smallest logically complete unit. Do not blend unrelated modifications.
4. **Idempotent & Safe Operations**
   - Avoid destructive bulk edits (mass renames, deletions, rewrites) unless explicitly scoped and confirmed by the user.
5. **Transparency of Assumptions**
   - Clearly label inferred assumptions; do not fabricate APIs, data models, or build steps.
6. **Reversibility**
   - Prefer additive changes (new files, new functions) over irreversible edits. Deprecate before deletion when practical.
7. **Validation Before Completion**
   - Attempt local build/test/lint verification (where tooling access allows) before declaring a task complete.
8. **Style & Convention Adherence**
   - Follow repository naming, formatting, and structural patterns; consult existing analogous modules first.
9. **Explicit Risk Surfacing**
   - Flag potential coupling, performance, security, or migration risks early in the workflow.
10. **No Secret Exposure**
    - Do not surface, copy, or transform sensitive tokens, credentials, or keys if encountered.
11. **License & Header Integrity**
    - Preserve existing license headers and notices unless explicitly modifying them per maintainer instruction.
12. **Documentation Integrity**
    - Do not remove human-authored rationale or architectural intent; if unclear, annotate rather than overwrite.
13. **Controlled Automation Scope**
    - Do not introduce or modify CI/CD automation, scripts, or infra-level configs without explicit user direction.
14. **Reference Universal Constraints**
    - Every workflow spec must include a reference link to this file in a "References" or equivalent section.
15. **Escalation on Ambiguity**
    - If conflicting instructions or unclear goals arise, pause and request clarification instead of guessing.
16. **Maintain Link Integrity After Refactoring**
    - After any file move, rename, or deletion, perform a repository-wide search for references to the old path(s) and propose corrections as part of the same logical change.
17. **Adapt Existing Patterns Before Creating New Ones**
    - Before proposing a new architectural pattern, mechanism, or API, analyze the existing codebase to find analogous patterns and adapt them when possible.
18. **Verify File Creation**
    - After creating a new file, read it back to ensure the contents match what was intended. If incorrect, report and correct immediately.

Conditional Constraints (SHOULD)
--------------------------------

- Prefer draft/review cycles for large architectural edits.
- Provide alternative solution sketches when trade-offs are material.
- Annotate generated code with concise intent comments where non-obvious.

Prohibited Actions (NEVER)
--------------------------

- Silent rewrites of large code regions.
- Generating fake benchmark, test, or coverage data.
- Removing failing tests instead of diagnosing root cause.
- Introducing dependencies without justification + impact note.

Workflow Author Requirements
----------------------------

Each workflow definition MUST:
- Include a "Compliance" subsection referencing this document.
- Avoid restating these constraints verbatim (reference instead) unless tightening.
- Document any approved exceptions (who authorized, scope, expiration).

Exception Handling
------------------

1. **Request**: Propose an exception in a PR/issue with rationale + risk.
2. **Review**: Maintainer evaluates necessity and scope.
3. **Grant**: Record in the workflow doc under "Exceptions" with timestamp.
4. **Sunset**: Remove or renew explicitly; expired exceptions are void.

Template Snippet (Embed in Workflows)
-------------------------------------

```
Compliance: This workflow adheres to all items in `docs/ai/system/universal-workflow-constraints.md` (no commits/pushes without explicit user direction). No exceptions granted.
```

See Also
--------

- Tutorial: `docs/ai/diataxis/tutorials/guardrail-orientation.md`
- How-To: `docs/ai/diataxis/how-to-guides/task-sequences.md`
