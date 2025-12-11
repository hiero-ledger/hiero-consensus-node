---
title: Define AI Build Instructions
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: advanced
duration: 45m
---

## Purpose

Co-design the build/test playbook the AI must follow before declaring work complete, including when to run module-specific commands, how to capture logs, and how to document failures.

## Prerequisites

- [ ] `gradle-conventions-lab-medium` tutorial completed or Gradle commands curated.
- [ ] Familiarity with your CI pipeline expectations.
- [ ] Willingness to update workflow specs (e.g., implementation or review workflows).

## Steps

1. **Define Success Criteria**
   - List the validations you expect per change type (docs-only, code change, proto update, etc.).
   - Decide which Gradle commands (compile, test, assemble, spotless) correspond to each scenario.
2. **Map Commands to Workflows**
   - Identify which workflows need explicit build instructions (e.g., upcoming SDLC stages, code review fix application, documentation updates).
   - For each workflow, note the minimum commands and any optional commands.
3. **Capture Failure Handling**
   - Determine how the AI should report failures: log file paths, summarized errors, task sequence entries.
   - Decide whether retries or escalations are allowed.
4. **Codify Instructions**
   - Create or update a reference doc (e.g., `docs/ai/diataxis/reference/build-instructions.md`) describing the matrix of change type → commands → success criteria.
   - Alternatively, add a section to each relevant workflow spec under "Validation".
5. **Update Workflows**
   - Use `design workflow` or manual edits to embed the instructions into the workflows' steps and validation sections.
   - Ensure the invocation table highlights any new required clarifications (e.g., "which modules changed?").
6. **Automate Reminders**
   - Add TODOs or tutorial steps to revisit the build matrix periodically.
   - Consider creating a task sequence to track pending workflow updates if multiple files are involved.

## Reflection

- Are the documented build commands sufficient for your CI expectations?
- Which workflows still need to be updated with the new instructions?
- Do you need additional tutorials to train collaborators on the new process?
