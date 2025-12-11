---
title: Run Tutorial Workflow
status: draft
diataxis: reference
audience: [AI agents]
mode: R->M
last-validated: 2025-12-11
references:
- ../universal-workflow-constraints.md
---

# AI Workflow: Run Tutorial

Compliance: This workflow adheres to all items in `docs/ai/system/universal-workflow-constraints.md`.

Intent
------

Facilitate a tutorial stored under `docs/ai/diataxis/tutorials/` by loading its content, presenting each step to the human collaborator, and optionally helping generate artifacts when the tutorial calls for agent assistance.

Tutorial Format Expectations
----------------------------

Each tutorial must include:
1. YAML front matter with `title`, `status`, `diataxis: tutorial`, `last-validated`, and optional `complexity`, `duration`, or `prerequisites` keys.
2. Sections (by heading) for:
   - `## Purpose` – context for the lesson.
   - `## Prerequisites` – checklist of environmental or knowledge requirements.
   - `## Steps` – ordered list (numbered or checkbox) describing the tutorial flow. Each step may include sub-bullets describing collaborative tasks.
   - `## Reflection` – prompts for recording outcomes or preferences (optional but encouraged).

Preconditions
-------------

1. Requested tutorial file exists under `docs/ai/diataxis/tutorials/`.
2. Working tree is clean OR user consents to proceed with existing changes.
3. User is available to answer prompts between steps.

Inputs
------

1. Tutorial slug (file stem, e.g., `guardrail-orientation`). If omitted, the workflow lists available tutorials and asks the user to choose.
2. Optional step selection (if the user wants to resume mid-way).

Steps (Agent Execution)
-----------------------

1. **Tutorial Discovery**
   - List tutorial files in `docs/ai/diataxis/tutorials/` (sorted by filename or `complexity`).
   - If no slug provided, present the list (title, complexity, duration) and prompt the user to choose.
2. **Load Tutorial Metadata**
   - Read the tutorial file.
   - Parse front matter to extract title, status, last-validated date, complexity, and duration.
   - Summarize metadata back to the user and confirm they wish to proceed.
3. **Prerequisite Check**
   - Parse the `## Prerequisites` section (checkbox or list) and ask the user to confirm each item. Offer guidance (e.g., point to docs) if an item is missing.
   - If prerequisites are unmet, either pause (per user request) or continue after capturing an explicit override.
4. **Step Loop**
   - Parse the `## Steps` section into ordered tasks.
   - For each step:
     1. Present the instruction text to the user.
     2. Ask whether the user wants the agent to perform any described sub-task (e.g., create a file, run a command).
     3. If the step requires edits, follow universal constraints: preview planned changes, request approval, then execute.
     4. Capture notes or decisions if the tutorial prompts for reflection at that step.
     5. Await user confirmation before moving to the next step.
5. **Reflection & Summary**
   - After completing all steps, read the `## Reflection` section (if present) and prompt the user for answers.
   - Summarize outcomes: what was configured, files created, open questions, and whether follow-up tutorials or workflows were identified.
6. **Optional Artifact Handling**
   - If the tutorial instructs the agent to create or update files, ensure those files are verified (constraint #18) and paths are reported to the user.
   - Offer to convert outcomes into a task sequence (e.g., if the tutorial uncovered future work) and follow the task sequence creation protocol if approved.

Outputs
-------

- Live guidance through the tutorial steps.
- Summary of completed steps, created artifacts, and follow-up items.
- Optional task sequence for future work.

Validation
----------

- Tutorial metadata accurately echoed to the user before execution.
- Each step acknowledged, with explicit user confirmation before proceeding.
- Any file modifications performed only after user approval and reported clearly.
- Tutorial completion summary delivered to the user.

Failure Modes & Mitigations
---------------------------

| Failure | Cause | Mitigation |
|---------|-------|------------|
| Tutorial file missing | Incorrect slug or deleted file | Re-list available tutorials and prompt for a valid choice. |
| Prerequisites unmet | Required context/tools not ready | Pause tutorial; offer to resume later or provide instructions for satisfying prerequisites. |
| Step ambiguity | Tutorial text unclear | Ask the user to clarify intent or update the tutorial doc via the design workflow. |
| Long-running edits | Tutorial step implies large changes | Create a task sequence and pause execution until user approves the broader plan. |

Exit Conditions
---------------

The workflow concludes when the tutorial steps are completed (or intentionally paused), outcomes summarized, and any follow-up artifacts noted.
