---
title: Task Sequence: <replace-with-name>
status: active
created: 2025-12-11
last-updated: 2025-12-11
branch: <current-branch-or-N/A>
workflow: <optional-related-workflow-file>
owner: ai-agent
purpose: >
  Short one or two line description of the multi-step effort.
related-paths:
- <optional/path/one>
- <optional/path/two>
---

## Overview

This sequence captures the minimal, linear set of steps to accomplish the stated purpose above. Read it top-down; resume by locating the first unchecked item. Only modify:
- checkbox states
- last-updated date
- append new decisions (do not rewrite prior rationale)
Scope increases require a decision log entry. Keep diffs small and avoid reordering unless justified.

## Task Checklist

- [ ] Define scope & confirm base branch
- [ ] Collect diff vs main
- [ ] Identify impacted components
- [ ] Map to candidate docs/code
- [ ] Classify freshness & rationale
- [ ] Prepare impact report
- [ ] Present report for approval
- [ ] Apply approved minimal edits
- [ ] Re-validate & finalize

## Resumption Context

List workflows or documents that must be loaded before resuming (e.g., `docs/ai/system/workflows/<name>.md`).

## Context

2025-12-11 Initial creation. Scope defined; awaiting first diff collection step.

## Decisions

<!-- Append-only. Use ISO timestamps. Example: -->
2025-12-11T15:00Z Adopt minimal header insertion strategy for missing status blocks.

## Risks

| ID | Description | Impact | Likelihood | Mitigation | Status |
|----|-------------|--------|------------|------------|--------|
| R1 | Potential over-selection of docs via broad grep | Medium | Low | Narrow search to module scope | Open |

## Notes

Scratchpad for transient observations (may be cleared before completion).

<!-- Completion Procedure:
1. Ensure all checkboxes are [x]
2. Set status: completed
3. Update last-updated date
4. Add final Context summary
5. (After user approval) delete this file or move it to docs/ai/tasks/tabled/ to prevent future scans
-->
