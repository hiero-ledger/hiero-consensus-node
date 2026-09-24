# GitHub Actions Workflow Duplication Report

**Date:** 2026-05-12  
**Scope:** 64 workflow files in `.github/workflows/`  
**Estimated duplicated lines:** ~1,950  

---

## Executive Summary

Seven repeated patterns were found across the workflow suite. The two highest-impact ones — a failure-mode calculation step and a Rootly+Slack notification block — account for roughly **1,435 duplicated lines** on their own and are strong candidates for extraction into a composite action and a reusable workflow respectively.

| # | Pattern | Files | Occurrences | Est. lines duplicated | Priority |
|---|---|---|---|---|---|
| 1 | Failure-mode output calculation | 11 | 24 | ~720 | **High** |
| 2 | Rootly + Slack notification block | 13 | 13 | ~715 | **High** |
| 3 | Harden Runner + Checkout | 41 | 41+ | ~660 | Medium |
| 4 | GCP auth + SDK setup | 14 | 14 | ~200 | Medium |
| 5 | Teleport + GCP auth chain | 6 | 6 | ~250 | Medium |
| 6 | Java + Gradle setup | 8 | 8 | ~100 | Low |
| 7 | Shared `workflow_call` input definitions | 7 | 7× each input | ~300 | Low |

---

## Pattern 1 — Failure-mode output calculation (HIGH)

### What it is

Every reusable test workflow ends a job with an identical bash step that inspects step outcomes and classifies the run as `none`, `test`, or `workflow` failure, writing the result to `$GITHUB_OUTPUT`.

### Scale

**11 files · 24 total occurrences · ~35 lines each**

Files affected:
- `zxc-compile-and-spotless-check.yaml`
- `zxc-dependency-module-check.yaml`
- `zxc-execute-hammer-tests.yaml`
- `zxc-execute-hapi-tests.yaml` (**9 occurrences** — one per job variant)
- `zxc-execute-integration-tests.yaml`
- `zxc-execute-otter-tests.yaml`
- `zxc-execute-timing-sensitive-tests.yaml`
- `zxc-execute-unit-tests.yaml`
- `zxc-snyk-scan.yaml`
- `zxc-verify-docker-build-determinism.yaml`
- `zxc-verify-gradle-build-determinism.yaml`

### Representative code (`zxc-execute-unit-tests.yaml`, lines 130–164)

```yaml
      - name: Set Failure Mode Output
        id: set-failure-mode
        if: ${{ always() }}
        run: |
          workflow_statuses=(
            "${{ steps.prepare-runner.outcome || 'skipped' }}"
            "${{ steps.publish-unit-test-report.outcome || 'skipped' }}"
            "${{ steps.upload-artifacts.outcome || 'skipped' }}"
            "${{ steps.publish-codecov.outcome || 'skipped' }}"
            "${{ steps.publish-codacy.outcome || 'skipped' }}"
            "${{ steps.upload-test-reports.outcome || 'skipped' }}"
          )
          test_statuses=(
            "${{ steps.gradle-test.outcome || 'skipped' }}"
          )

          failure_mode="none"

          has_failure() {
            local arr=("$@")
            for status in "${arr[@]}"; do
              [[ "${status}" == "failure" ]] && return 0
            done
            return 1
          }

          if has_failure "${test_statuses[@]}"; then
            failure_mode="test"
          fi

          if has_failure "${workflow_statuses[@]}"; then
            failure_mode="workflow"
          fi

          echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
```

### What varies

Only the step IDs listed in `workflow_statuses` and `test_statuses` differ between files. The `has_failure()` function, the three-way classification logic, and the output line are **byte-for-byte identical** in all 24 occurrences.

### Recommendation

Extract to a composite action at `.github/actions/set-failure-mode/action.yml`.

Accept two inputs — `workflow-step-ids` and `test-step-ids` — as newline- or comma-separated strings. The action reads them and builds the arrays at runtime. Each call site shrinks from ~35 lines to:

```yaml
      - name: Set Failure Mode Output
        id: set-failure-mode
        if: ${{ always() }}
        uses: ./.github/actions/set-failure-mode
        with:
          workflow-step-ids: |
            prepare-runner
            publish-unit-test-report
            upload-artifacts
          test-step-ids: gradle-test
```

**Estimated savings: ~720 lines** (24 × 30 lines)

---

## Pattern 2 — Rootly + Slack failure notification block (HIGH)

### What it is

Failure-reporting jobs share a multi-step sequence that builds a Rootly incident summary, logs it to the step summary, creates a Rootly alert, and sends Slack notifications. This block is typically 55–70 lines per occurrence.

### Scale

**13 files · 13 occurrences · ~55 lines each**

Files affected:
- `node-flow-build-application.yaml`
- `node-flow-deploy-release-artifact.yaml`
- `zxc-merge-queue-performance-test.yaml`
- `zxc-single-day-performance-test.yaml`
- `zxcron-extended-test-suite.yaml`
- `zxcron-promote-build-candidate.yaml`
- `zxf-merge-queue-performance-test-controller-adhoc.yaml`
- `zxf-merge-queue-performance-test-controller.yaml`
- `zxf-single-day-canonical-test.yaml`
- `zxf-single-day-longevity-test-controller-adhoc.yaml`
- `zxf-single-day-longevity-test-controller.yaml`
- `zxf-single-day-performance-test-controller-adhoc.yaml`
- `zxf-single-day-performance-test-controller.yaml`

### Representative code (`node-flow-build-application.yaml`, lines 182–245)

```yaml
      - name: Set Rootly Parameters
        id: set-rootly-parameters
        if: ${{ steps.report-category.outputs.category == 'test' || steps.report-category.outputs.category == 'environment' }}
        run: |
          ROOTLY_SERVICE_NAME="CITR General"
          if [[ "${{ steps.report-category.outputs.category }}" == "environment" ]]; then
            ROOTLY_SERVICE_NAME="CI/CD Workflows"
          fi
          echo "service=${ROOTLY_SERVICE_NAME}" >> "${GITHUB_OUTPUT}"

      - name: Build Rootly Summary
        id: rootly-summary
        if: ${{ steps.report-category.outputs.category == 'test' || steps.report-category.outputs.category == 'environment' }}
        env:
          PRINT_FAILED_TESTS_SCRIPT: ".github/workflows/support/scripts/print-failed-tests.sh"
        run: |
          title="MATS Error Report"
          echo "title=${title}" >> "${GITHUB_OUTPUT}"
          {
            echo 'summary<<EOF'
            echo "Status of each jobs:"
            echo "- MATS Tests: ${{ needs.mats-tests.result }}"
            echo "- Deploy CI Triggers: ${{ needs.deploy-ci-trigger.result }}"
            if [[ "${{ needs.mats-tests.result }}" != "success" ]]; then
              echo "MATS tests failed due to a ${{ needs.mats-tests.outputs.failure-mode }} error."
              bash "${{ github.workspace }}/${{ env.PRINT_FAILED_TESTS_SCRIPT }}" "${{ needs.mats-tests.outputs.failed-tests }}"
            fi
            echo "- Author: ${{ steps.find-commit-author-slack.outputs.slack-user-id }}"
            echo "- Commit: <${{ github.server_url }}/${{ github.repository }}/commit/${{ github.sha }}>"
            echo "- Workflow: <${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}>"
            echo EOF
          } >> "${GITHUB_OUTPUT}"

      - name: Log Rootly Summary
        if: ${{ steps.report-category.outputs.category == 'test' || steps.report-category.outputs.category == 'environment' }}
        run: |
          echo "### Title: ${{ steps.rootly-summary.outputs.title }}" >> "${GITHUB_STEP_SUMMARY}"
          echo "${{ steps.rootly-summary.outputs.summary }}" >> "${GITHUB_STEP_SUMMARY}"

      - name: Create Rootly Alert
        id: create-rootly-alert
        if: ${{ steps.report-category.outputs.category == 'test' || steps.report-category.outputs.category == 'environment' }}
        uses: pandaswhocode/rootly-alert-action@fdae1529e5aed62040016accf719a0ceb7dae57f # v1.0.0
        continue-on-error: true
        with:
          api_key: ${{ secrets.ROOTLY_API_TOKEN }}
          summary: "${{ steps.rootly-summary.outputs.title }}"
          details: "${{ steps.rootly-summary.outputs.summary }}"
          notification_target_type: "Service"
          notification_target: ${{ steps.set-rootly-parameters.outputs.service }}
          set_as_noise: "false"
          alert_urgency: "High"
          external_id: ${{ github.run_id }}
          external_url: "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}"
          environments: "CITR"
```

### What varies

The `Build Rootly Summary` step content differs per workflow (it references different `needs.*` outputs). The `Create Rootly Alert` action call and `Log Rootly Summary` step are structurally identical. In some files `notification_target` is hardcoded rather than resolved via `Set Rootly Parameters`.

### Recommendation

The variation in the summary body makes a composite action impractical. A **reusable `workflow_call` workflow** (`zxc-notify-failure.yaml`) is a better fit — accept `title`, `summary`, `service`, and `slack-webhook` as string inputs, and run the Log + Create Rootly Alert + Slack steps internally. Each caller pre-builds the summary string locally (a single bash step) and then delegates the notification entirely.

**Estimated savings: ~715 lines** (13 × ~55 lines)

---

## Pattern 3 — Harden Runner + Checkout (MEDIUM)

**41 files · ~16 lines each**

The sequence `step-security/harden-runner` (egress-policy: audit) → `actions/checkout` (fetch-depth: 0) appears at the top of almost every non-reusable job. In the `zxc-execute-*` family this is already consolidated inside `pandaswhocode/initialize-github-job`, but the remaining 20+ files inline it manually.

**Recommendation:** Extend usage of `pandaswhocode/initialize-github-job` to the remaining files, or create a minimal composite action for workflows that don't need the full Java+Gradle setup.

**Estimated savings: ~330 lines** (applying only to the ~20 files not already using the consolidating action)

---

## Pattern 4 — GCP Authentication + SDK Setup (MEDIUM)

**14 files · ~14 lines each**

`step-security/google-github-auth` followed by `google-github-actions/setup-gcloud` with near-identical `with:` parameters (same workload identity pool and service account) appears in 14 files.

**Recommendation:** Composite action at `.github/actions/setup-gcp/action.yml`. Saves ~14 lines per caller.

**Estimated savings: ~196 lines**

---

## Pattern 5 — Teleport + GCP Auth Chain (MEDIUM)

**6 files · ~42 lines each**

Performance and longevity test workflows share a 6-step infra setup sequence:
1. Install KubeCtl
2. Install Teleport Client
3. Authorize Teleport SSH Access
4. Authorize Teleport K8S Access
5. Authenticate to Google Cloud
6. Setup Google Cloud SDK

All six files use the same `proxy`, `token`, and `certificate-ttl` values for Teleport.

Files: `zxc-execute-performance-test.yaml`, `zxc-merge-queue-performance-test.yaml`, `zxc-single-day-longevity-test.yaml`, `zxc-single-day-performance-test.yaml`, and two others.

**Recommendation:** Composite action at `.github/actions/setup-teleport-gcp/action.yml`.

**Estimated savings: ~250 lines**

---

## Pattern 6 — Java + Gradle Setup (LOW)

**8 files · ~12 lines each**

`actions/setup-java` (distribution: temurin, java-version: 25) followed by `gradle/actions/setup-gradle` with consistent caching parameters. Already handled in test workflows via `pandaswhocode/initialize-github-job`.

**Recommendation:** Low priority given existing consolidation. Normalise the 8 remaining files to use the shared action when convenient.

**Estimated savings: ~96 lines**

---

## Pattern 7 — Shared `workflow_call` Input Definitions (LOW)

**7+ files · ~8 lines per input**

The following inputs appear with identical names, types, defaults, and descriptions across multiple reusable workflows:

| Input | Files | Definition |
|---|---|---|
| `ref` | 7 | `string`, required: false, default: `""` |
| `java-version` | 7 | `string`, default: `"25"` |
| `java-distribution` | 6 | `string`, default: `"temurin"` |
| `node-version` | 5 | `string`, default: `"20"` |

GitHub Actions has no mechanism to share input definitions across workflows (no import/include for `on.workflow_call.inputs`). Deduplication here is not possible without tooling.

**Recommendation:** Document the canonical values in a comment block or in a `.github/workflow-input-conventions.md` reference file to prevent drift.

---

## Total Estimated Savings

| Pattern | Lines saved |
|---|---|
| Failure-mode calculation | ~720 |
| Rootly + Slack block | ~715 |
| Harden Runner + Checkout (partial) | ~330 |
| Teleport + GCP chain | ~250 |
| GCP auth + SDK | ~196 |
| Java + Gradle | ~96 |
| **Total** | **~2,307 lines** |

Implementing patterns 1 and 2 alone would eliminate roughly **1,435 lines** of duplicated configuration and reduce the blast radius of any future changes to the failure reporting or notification logic to a single file each.
