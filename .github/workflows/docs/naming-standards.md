# GitHub Action Naming Standards

This document outlines the conventions and best practices for naming GitHub workflow files,
organizing workflow directories, and structuring workflow definitions.
It is intended to ensure consistency, readability, and maintainability across all repository workflows.

## Workflow Naming Standards:

- 3-digit prefix (000 to 999)
  - 000 User-centric workflows sorted by priority/use
  - 100 Operational workflows (manual-run RE flows)
  - 200 CITR workflows
    - 200 CITR manual run
    - 220 CITR daily runs
    - 240 CITR longer runs
  - 300 Trigger-based `main` workflows in the
  - 400 TBD
  - 500 TBD
  - 600 TBD
  - 700 TBD
  - 800 Reusable workflows
  - 900 Cron tasks prefixed by 900 to sort to the very bottom
- Followed by `: ` (colon and a space)
- Followed by square-bracket notation `[XXXX] ` followed by a space
  - `[USER]` = called by user directly workflow dispatch
  - `[FLOW]` = flow (pull_request.branches.main | pull_request.branches.release/** | pull_request.tags.v*), push, PR Target, etc.
  - `[CALL]` = reusable (workflow_call)
  - `[CRON]` = scheduled (schedule)
  - `[DISP]` = internal dispatchable (workflow dispatch triggered by other workflows, not end users)
- Followed by the name of the workflow, maximum of 21 characters
- Workflow Naming Notes:
  - Use proper casing
  - Separator used should be spaces

## File Naming Standards:

- 3-digit prefix (000 to 999)

  | Prefix | Category / Description         | Notes / Subcategory               |
  |--------|--------------------------------|-----------------------------------|
  | 000    | User-centric workflows         | Sorted by priority/use            |
  | 100    | Operational workflows          | Manual-run RE flows               |
  | 200    | CITR workflows (all workflows) |                                   |
  | 200    | CITR manual run                | Adhoc runs                        |
  | 220    | CITR daily runs                | Automatic daily runs              |
  | 240    | CITR longer runs               | Multi-day runs                    |
  | 300    | Trigger-based `main` workflows |                                   |
  | 400    | TBD                            |                                   |
  | 500    | TBD                            |                                   |
  | 600    | TBD                            |                                   |
  | 700    | TBD                            |                                   |
  | 800    | Reusable workflows             |                                   |
  | 900    | Cron tasks                     | Prefixed by 900 to sort to bottom |

- Followed by a hyphen `-`
- Followed by the workflow code (see table below)

  | Workflow Code | Description                                                                           |
  |---------------|---------------------------------------------------------------------------------------|
  | `[USER]`      | Called by user directly via workflow dispatch                                         |
  | `[FLOW]`      | Triggered through some manner (PR Target, Branch Push, or Tag Push)                   |
  | `[CALL]`      | Reusable workflow (`workflow_call`)                                                   |
  | `[CRON]`      | Scheduled workflow (`schedule`)                                                       |
  | `[DISP]`      | Internal dispatchable (workflow dispatch triggered by other workflows, not end users) |

- Followed by a hypen `-`
- Followed by the workflow name, maximum of 30 characters
- Followed by`.yaml`
- File Naming Notes:
  - All letters in filename should be lowercase
  - Separator used should be a hyphen
  - No special characters are allowed in filename
