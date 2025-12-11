---

title: Design Workflow
status: Draft
diataxis: reference
audience: [AI agents]
mode: R->M
last-validated: 2025-10-17
references:
- ../universal-workflow-constraints.md
-------------------------------------

# AI Workflow: Design New Workflow

Compliance: This workflow adheres to all items in `docs/ai/system/universal-workflow-constraints.md`.

Intent
------

To collaboratively design and create a new, compliant AI workflow based on a high-level natural language description from a user. This workflow automates the process of translating a user's goal into a structured, executable workflow file and registering it as a command.

Preconditions
-------------

1. The agent has write access to the `docs/ai/system/workflows/` directory.

Inputs
------

1. **Goal Description:** A natural language description from the user detailing the behavior of the desired new workflow (e.g., "I want a workflow that finds all TODO comments in the code and lists them in a report").

Steps (Agent Execution)
-----------------------

1. **Clarification and Analysis:**

* Engage in a dialogue with the user to refine the `Goal Description`.
* From the description, derive the key components of a workflow:
  * A `kebab-case-name` for the workflow file.
  * The `Intent` (the "why").
  * The `Inputs` required to run the workflow.
  * The `Steps (Agent Execution)` needed to accomplish the goal.
  * The `Validation` criteria for success.
  * A primary `trigger` phrase for the command.

2. **Review for Ambiguity:**

* The agent MUST review the proposed `Steps` from the perspective of an AI agent.
* The agent MUST identify any steps that could be misinterpreted or are not sufficiently explicit.
* Any identified ambiguities MUST be resolved before proceeding.

3. **Verify Documentation Location (for non-workflow docs):**

* If the artifact being created is not a workflow (e.g., it is `explanation` or `how-to-guides` documentation), consult `docs/ai/diataxis/` (see `docs/ai/diataxis/explanation/kernel-overview.md`) to determine the correct subdirectory based on the Diátaxis framework.
* State the chosen directory and its rationale. "This document provides understanding-oriented content, so it will be placed in `docs/ai/diataxis/explanation/`."

4. **Generate Proposed Artifacts:**

* Based on the analysis, generate the complete content for the new artifact file at its correct path.
* If a new workflow was created, generate a new markdown table row for the `invocations.md` file.

5. **Confirmation and Explanation:**

* Present the proposed file content to the user for review.
* If a new workflow was created, explain how to invoke the new command using its proposed trigger.
* Explain the expected results of running the command.
* Ask for explicit approval: "Is this correct? Shall I create this file and register the command? (yes/no)"

6. **Finalize (On Approval):**

* If the user approves, create the new file at the specified path.
* If a new workflow was created, atomically update the `invocations.md` file to include the new command row.
* Report the successful creation to the user.

Validation
----------

This workflow is considered successful if the user confirms the proposed workflow is correct and the agent successfully creates the file and updates the invocation table.

Failure Modes & Mitigations
---------------------------

|        Failure        |                          Cause                           |                                          Mitigation                                          |
|-----------------------|----------------------------------------------------------|----------------------------------------------------------------------------------------------|
| User rejects proposal | The generated workflow does not match the user's intent. | Return to the "Clarification and Analysis" step to refine the design based on user feedback. |
| File creation fails   | Filesystem or permission error.                          | Report the error to the user and abort.                                                      |

Exit Conditions
---------------

The workflow concludes when the new workflow is created with user approval, or when the user decides not to proceed.
