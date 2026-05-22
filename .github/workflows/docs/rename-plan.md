# Workflow File Rename Plan

This document lists every workflow file that needs to be renamed (and/or have its `name:` field updated) to comply with the naming standard defined in `naming-standards.md`.

**Format reminder:**
- File: `ddd-xxxx-<name>.yaml` — name portion ≤ 30 chars, lowercase, hyphens
- YAML `name:`: `ddd: [XXXX] <name>` — name portion ≤ 21 chars, Title Case, spaces
- Valid codes: `user`/`[USER]`, `flow`/`[FLOW]`, `call`/`[CALL]`, `cron`/`[CRON]`, `disp`/`[DISP]`
- `[CITR]` is not a valid code — "CITR" is moved into the 21-char name portion

---

## Files Already Have a Numeric Prefix — Minor Fixes Needed

| Current File | New File | Current `name:` | New `name:` | Notes |
|---|---|---|---|---|
| `100-flow-update-solo-version-vars.yaml` | `100-user-update-solo-version-vars.yaml` | `100: [FLOW] Update Solo Version Variables` | `100: [USER] Update Solo Vers Vars` | Trigger is `workflow_dispatch` only → `user`, not `flow`; name was >21 chars |
| `200-flow-dry-run-extended-test-suite.yaml` | `200-user-dry-run-extended-test-suite.yaml` | `200: [FLOW] XTS Dry Run` | `200: [USER] XTS Dry Run` | Trigger is `workflow_dispatch` only → `user`, not `flow` |
| `801-read-chewie-response.yaml` | `800-call-read-chewie-response.yaml` | `801: [CITR] Read Chewie Response` | `800: [CALL] Read Chewie Response` | Filename missing the code segment; `[CITR]` → `[CALL]` (trigger is `workflow_call`) |

---

## Files With No Numeric Prefix — Full Rename Required

### 300 — Trigger-Based Main Workflows (PR / Push / Tag push)
*Top-level workflows. Called directly by GitHub events, not by other workflows.*

| Current File | New File | Current `name:` | New `name:` |
|---|---|---|---|
| `flow-pull-request-formatting.yaml` | `300-flow-pull-request-formatting.yaml` | `PR Formatting` | `300: [FLOW] PR Formatting` |
| `node-flow-pull-request-checks.yaml` | `300-flow-pull-request-checks.yaml` | `Node: PR Checks` | `300: [FLOW] PR Checks` |
| `node-flow-build-application.yaml` | `300-flow-build-application.yaml` | `Node: Build Application` | `300: [FLOW] Build Application` |
| `node-flow-deploy-release-artifact.yaml` | `300-flow-deploy-release-artifact.yaml` | `ZXF: Deploy Production Release` | `300: [FLOW] Deploy Prod Release` |
| `flow-generate-release-notes.yaml` | `300-flow-generate-release-notes.yaml` | `Generate Release Notes` | `300: [FLOW] Generate Rel Notes` |
| `node-zxf-snyk-monitor.yaml` | `300-flow-snyk-monitor.yaml` | `ZXF: Snyk Monitor` | `300: [FLOW] Snyk Monitor` |
| `zxf-publish-yahcli-image.yaml` | `300-flow-publish-yahcli-image.yaml` | `ZXC: Publish Yahcli Image` | `300: [FLOW] Publish Yahcli Image` |

### 100 — Operational Workflows (Manual / User-Run)
*Top-level workflows. Started manually via the GitHub Actions UI.*

| Current File | New File | Current `name:` | New `name:` | Code |
|---|---|---|---|---|
| `flow-artifact-determinism.yaml` | `100-user-artifact-determinism.yaml` | `Artifact Determinism` | `100: [USER] Artifact Determinism` | user |
| `flow-increment-next-main-release.yaml` | `100-user-increment-next-main-release.yaml` | `[Release] Increment Version File` | `100: [USER] Increment Main Rel` | user |
| `flow-trigger-release.yaml` | `100-user-trigger-release.yaml` | `[Release] Create New Release` | `100: [USER] Trigger Release` | user |
| `node-flow-deploy-adhoc-artifact.yaml` | `100-user-deploy-adhoc-artifact.yaml` | `Node: Deploy Adhoc Release` | `100: [USER] Deploy Adhoc Artifact` | user |
| `node-flow-deploy-preview.yaml` | `100-user-deploy-preview.yaml` | `Node: Deploy Preview` | `100: [USER] Deploy Preview` | user |
| `node-zxf-deploy-integration.yaml` | `100-user-deploy-integration.yaml` | `ZXF: [Node] Deploy Integration Network Release` | `100: [USER] Deploy Integration` | user |
| `zxf-update-gs-state-variable.yaml` | `100-user-update-gs-state-variable.yaml` | `ZXF: Update GS_STATE Variable` | `100: [USER] Update GS State Var` | user |
| `zxf-collect-workflow-logs.yaml` | `100-disp-collect-workflow-logs.yaml` | `ZXF: Collect Workflow Run Logs` | `100: [DISP] Collect Workflow Logs` | disp — input is a `workflow_run_id`, called internally |

### 200 — CITR Workflows (Manual and Internally Dispatched)
*Top-level workflows. Mix of user-initiated adhoc runs and internally dispatched controllers.*

| Current File | New File | Current `name:` | New `name:` | Code |
|---|---|---|---|---|
| `flow-dry-run-mats-suite.yaml` | `200-user-dry-run-mats-suite.yaml` | `[CITR] MATS Dry Run` | `200: [USER] CITR MATS Dry Run` | user |
| `zxf-merge-queue-performance-test-controller-adhoc.yaml` | `200-user-mqpt-controller-adhoc.yaml` | `ZXF: [CITR] Adhoc - Merge Queue Performance Test Controller` | `200: [USER] CITR MQPT Ctrl Adhoc` | user |
| `zxf-single-day-longevity-test-controller-adhoc.yaml` | `200-user-sdlt-controller-adhoc.yaml` | `ZXF: [CITR] Adhoc - Single Day Longevity Test Controller` | `200: [USER] CITR SDLT Ctrl Adhoc` | user |
| `zxf-single-day-performance-test-controller-adhoc.yaml` | `200-user-sdpt-controller-adhoc.yaml` | `ZXF: [CITR] Adhoc - Single Day Performance Test Controller (SDPT)` | `200: [USER] CITR SDPT Ctrl Adhoc` | user |
| `zxf-merge-queue-performance-test-controller.yaml` | `200-disp-mqpt-controller.yaml` | `ZXF: [CITR] Merge Queue Performance Test Controller` | `200: [DISP] CITR MQPT Controller` | disp — no user-facing inputs; dispatched by other workflows |
| `zxf-prepare-extended-test-suite.yaml` | `200-disp-prepare-extended-test-suite.yaml` | `ZXF: [CITR] Prepare Extended Test Suite` | `200: [DISP] CITR Prepare XTS` | disp — part of promotion pipeline |
| `zxf-single-day-canonical-test.yaml` | `200-disp-single-day-canonical-test.yaml` | `ZXF: [CITR] Single Day Canonical Test (SDCT)` | `200: [DISP] CITR Single Day Canon` | disp — requires internal `build-tag` input |
| `zxf-single-day-performance-test-controller.yaml` | `200-disp-sdpt-controller.yaml` | `ZXF: [CITR] Single Day Performance Test Controller (SDPT)` | `200: [DISP] CITR SDPT Controller` | disp — no inputs; triggered by tag promotions |
| `zxf-single-day-longevity-test-controller.yaml` | `200-disp-sdlt-controller.yaml` | `ZXF: [CITR] Single Day Longevity Test Controller` | `200: [DISP] CITR SDLT Controller` | disp — no inputs; triggered by tag promotions |

### 800 — Reusable Sub-Workflows (workflow_call)
*Sub-workflows only. Called exclusively by other workflows, never triggered directly.*

| Current File | New File | Current `name:` | New `name:` |
|---|---|---|---|
| `zxc-block-node-regression.yaml` | `800-call-block-node-regression.yaml` | `ZXC: Block Node Regression` | `800: [CALL] Block Node Regression` |
| `zxc-build-publish-state-validator.yaml` | `800-call-build-publish-state-validator.yaml` | `ZXC: Build & Publish Hedera State Validator Uber JAR` | `800: [CALL] Build State Validator` |
| `zxc-compile-and-spotless-check.yaml` | `800-call-compile-and-spotless-check.yaml` | `ZXC: Compile and Spotless Check` | `800: [CALL] Compile And Spotless` |
| `zxc-create-github-release.yaml` | `800-call-create-github-release.yaml` | `ZXC: Create Github Release` | `800: [CALL] Create Github Release` |
| `zxc-dependency-module-check.yaml` | `800-call-dependency-module-check.yaml` | `ZXC: Dependency Module Check` | `800: [CALL] Dependency Module Chk` |
| `zxc-execute-hammer-tests.yaml` | `800-call-execute-hammer-tests.yaml` | `ZXC: Execute Hammer Tests` | `800: [CALL] Execute Hammer Tests` |
| `zxc-execute-hapi-tests.yaml` | `800-call-execute-hapi-tests.yaml` | `ZXC: Execute HAPI Tests` | `800: [CALL] Execute HAPI Tests` |
| `zxc-execute-integration-tests.yaml` | `800-call-execute-integration-tests.yaml` | `ZXC: Execute Integration Tests` | `800: [CALL] Execute Integ Tests` |
| `zxc-execute-otter-tests.yaml` | `800-call-execute-otter-tests.yaml` | `ZXC: Execute Otter Tests` | `800: [CALL] Execute Otter Tests` |
| `zxc-execute-performance-test.yaml` | `800-call-execute-performance-test.yaml` | `ZXC: [CITR] Execute Performance Test` | `800: [CALL] CITR Exec Perf Test` |
| `zxc-execute-timing-sensitive-tests.yaml` | `800-call-execute-timing-sensitive-tests.yaml` | `ZXC: Execute Timing Sensitive Tests` | `800: [CALL] Execute Timing Tests` |
| `zxc-execute-unit-tests.yaml` | `800-call-execute-unit-tests.yaml` | `ZXC: Execute Unit Tests` | `800: [CALL] Execute Unit Tests` |
| `zxc-jrs-regression.yaml` | `800-call-jrs-regression.yaml` | `ZXC: Regression` | `800: [CALL] JRS Regression` |
| `zxc-json-rpc-relay-regression.yaml` | `800-call-json-rpc-relay-regression.yaml` | `ZXC: JSON-RPC Relay Regression` | `800: [CALL] JSON-RPC Relay Reg` |
| `zxc-mats-tests.yaml` | `800-call-mats-tests.yaml` | `ZXC: Executable MATS Tests` | `800: [CALL] MATS Tests` |
| `zxc-merge-queue-performance-test.yaml` | `800-call-merge-queue-performance-test.yaml` | `ZXC: [CITR] Merge Queue Performance Test` | `800: [CALL] CITR MQ Perf Test` |
| `zxc-mirror-node-regression.yaml` | `800-call-mirror-node-regression.yaml` | `ZXC: Mirror Node Regression` | `800: [CALL] Mirror Node Regress` |
| `zxc-publish-production-image.yaml` | `800-call-publish-production-image.yaml` | `ZXC: Publish Production Image` | `800: [CALL] Publish Prod Image` |
| `zxc-single-day-longevity-test.yaml` | `800-call-single-day-longevity-test.yaml` | `ZXC: [CITR] Single Day Longevity Test` | `800: [CALL] CITR Single Day Long` |
| `zxc-single-day-performance-test.yaml` | `800-call-single-day-performance-test.yaml` | `ZXC: [CITR] Single Day Performance Test` | `800: [CALL] CITR Single Day Perf` |
| `zxc-snyk-scan.yaml` | `800-call-snyk-scan.yaml` | `ZXC: Snyk Scan` | `800: [CALL] Snyk Scan` |
| `zxc-tck-regression.yaml` | `800-call-tck-regression.yaml` | `ZXC: TCK Regression` | `800: [CALL] TCK Regression` |
| `zxc-verify-docker-build-determinism.yaml` | `800-call-verify-docker-determinism.yaml` | `ZXC: Verify Docker Build Determinism` | `800: [CALL] Verify Docker Build` |
| `zxc-verify-gradle-build-determinism.yaml` | `800-call-verify-gradle-determinism.yaml` | `ZXC: Verify Gradle Build Determinism` | `800: [CALL] Verify Gradle Build` |
| `zxc-xts-tests.yaml` | `800-call-xts-tests.yaml` | `ZXC: Executable XTS Tests` | `800: [CALL] XTS Tests` |
| `node-zxc-build-release-artifact.yaml` | `800-call-build-release-artifact.yaml` | `ZXC: [Node] Deploy Release Artifacts` | `800: [CALL] Build Release Art` |
| `node-zxc-deploy-preview.yaml` | `800-call-deploy-preview.yaml` | `ZXC: [Node] Deploy Preview Network Release` | `800: [CALL] Deploy Preview` |

### 900 — Cron / Scheduled Tasks
*Top-level workflows. Triggered by a schedule (cron expression), not manually or by other workflows.*

| Current File | New File | Current `name:` | New `name:` |
|---|---|---|---|
| `zxcron-auto-namespaces-delete.yaml` | `900-cron-auto-namespaces-delete.yaml` | `Delete automation Latitude Namespaces` | `900: [CRON] Auto Namespace Delete` |
| `zxcron-clean.yaml` | `900-cron-clean.yaml` | `CronClean Latitude Namespaces` | `900: [CRON] Clean Latitude NS` |
| `zxcron-extended-test-suite.yaml` | `900-cron-extended-test-suite.yaml` | `ZXCron: [CITR] Extended Test Suite` | `900: [CRON] CITR Ext Test Suite` |
| `zxcron-promote-build-candidate.yaml` | `900-cron-promote-build-candidate.yaml` | `ZXCron: [CITR] Promote Build Candidate` | `900: [CRON] CITR Promote Build` |
| `node-zxcron-release-branching.yaml` | `900-cron-release-branching.yaml` | `ZXCron: Automatic Release Branching` | `900: [CRON] Release Branching` |

---

## Files With No Changes Needed

| File | `name:` | Status |
|---|---|---|
| `050-user-memory-profile-ctrl.yaml` | `050: [USER] Memory Profile Ctrl` | ✅ Compliant |
| `080-flow-auto-unapprove.yaml` | `080: [FLOW] Auto Unapprove PR` | ✅ Compliant |
| `200-user-adhoc-solo-tests.yaml` | `200: [USER] Ad Hoc Solo Tests` | ✅ Compliant |
| `210-flow-merge-queue-controller.yaml` | `210: [FLOW] Merge Queue Controller` | ✅ Compliant |
| `700-flow-copilot-setup-steps.yaml` | `700: [FLOW] Copilot Setup Steps` | ✅ Compliant |

---

## Summary

| Category | Count |
|---|---|
| Files needing changes (minor fixes to partially-converted) | 3 |
| New 300-flow (trigger-based main) | 7 |
| New 100-user/disp (operational) | 8 |
| New 200-user/disp (CITR) | 9 |
| New 800-call (reusable sub-workflows) | 27 |
| New 900-cron (scheduled) | 5 |
| **Total files needing changes** | **59** |
| Files already compliant | 5 |

---

## Acronyms Used in Short Names
Due to the 21-character limit on `name:` values, some long names were abbreviated using established acronyms:

| Acronym | Meaning |
|---|---|
| CITR | Continuous Integration Test Runs |
| MQPT | Merge Queue Performance Test |
| SDCT | Single Day Canonical Test |
| SDLT | Single Day Longevity Test |
| SDPT | Single Day Performance Test |
| XTS | Extended Test Suite |
| MATS | (existing acronym in codebase) |
| NS | Namespaces |
| Rel | Release |
| Reg / Regress | Regression |
| Art | Artifact |
| Chk | Check |
| Perf | Performance |
| Prod | Production |
| Integ | Integration |
| Ext | Extended |
| Long | Longevity |
| Incr | Increment |
| Pub | Publish |
| Det | Determinism |

## Notes on Specific Decisions

- **`zxc-build-publish-state-validator.yaml`**: Has both `workflow_dispatch` and `workflow_call` triggers. Classified as `call` (800) since its primary use is as a reusable sub-workflow.
- **`zxc-verify-docker-build-determinism.yaml` / `zxc-verify-gradle-build-determinism.yaml`**: Filenames shortened from 31 to 25 chars (both exceed the 30-char limit: `verify-docker-build-determinism` = 31, `verify-gradle-build-determinism` = 31). The word "build" was dropped from the filename (retained in the YAML name as "Build").
- **`zxf-collect-workflow-logs.yaml`**: Classified as `disp` (not `user`) because its input is an internal `workflow_run_id`, indicating it is called by automation rather than end users.
- **`node-zxf-snyk-monitor.yaml`**: Classified as `flow` (300) because its primary trigger is a `push` to the main branch. The `workflow_dispatch` trigger has no inputs and is likely used only to re-trigger the scan manually.
- **`zxf-publish-yahcli-image.yaml`**: Classified as `flow` (300) because its primary trigger is `push` on version tags (`v*.*.*`).
