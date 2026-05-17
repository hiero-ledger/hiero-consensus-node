# Inline Slack Message Payload Locations

This document catalogues all locations within the `hiero-consensus-node` workflows where Slack notification payloads are constructed **inline** (either via `cat <<EOF > slack_payload.json` shell redirection or YAML multiline `payload: |` blocks). 

Following the **CITR Notification standard**, these inline payloads should eventually be refactored to use `gomplate` with centralized JSON templates hosted in [`.github/workflows/templates/`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/templates). This improves workflow yaml readability, guarantees correct JSON string escaping via `data.ToJSON`, and consolidates message styling.

---

## 📋 Catalog of Inline Slack Payload Builders

Below is a complete index of all 21 locations where Slack messages are built inline across the repository workflows:

| # | Workflow File | Line | Implementation Type | Target Webhook Secret / Input | Notification Context |
|---|---|---|---|---|---|
| 1 | [`zxc-json-rpc-relay-regression.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-json-rpc-relay-regression.yaml) | 223 | `cat <<EOF > slack_payload.json` | `secrets.slack-detailed-report-webhook` | JSON-RPC Relay Regression status report |
| 2 | [`zxc-merge-queue-performance-test.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-merge-queue-performance-test.yaml) | 938 | `cat <<EOF > slack_payload.json` | `secrets.slack-report-webhook` | Merge Queue Performance Test (MQPT) report |
| 3 | [`zxc-single-day-longevity-test.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-single-day-longevity-test.yaml) | 1124 | `cat <<EOF > slack_payload.json` | `secrets.slack-report-webhook` | Single Day Longevity Test report |
| 4 | [`zxc-single-day-performance-test.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-single-day-performance-test.yaml) | 1590 | `cat <<EOF > slack_payload.json` | `secrets.slack-report-webhook` | Single Day Performance Test report |
| 5 | [`zxc-tck-regression.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-tck-regression.yaml) | 251 | `cat <<EOF > slack_payload.json` | `secrets.slack-detailed-report-webhook` | SDK TCK Regression Test status report |
| 6 | [`zxcron-promote-build-candidate.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxcron-promote-build-candidate.yaml) | 226 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_BUILD_PROMOTION_WEBHOOK` | Build promotion candidacy status |
| 7 | [`zxcron-promote-build-candidate.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxcron-promote-build-candidate.yaml) | 313 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_BUILD_PROMOTION_WEBHOOK`, `secrets.SLACK_RELEASE_TEAM_WEBHOOK` | Promotion success / release tag creation |
| 8 | [`zxcron-promote-build-candidate.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxcron-promote-build-candidate.yaml) | 464 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_BUILD_PROMOTION_WEBHOOK`, `secrets.SLACK_RELEASE_TEAM_WEBHOOK` | Promotion workflow completion |
| 9 | [`zxf-merge-queue-performance-test-controller-adhoc.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml) | 353 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_OPERATIONS_WEBHOOK` | Ad-hoc MQPT controller failure report |
| 10 | [`zxf-single-day-longevity-test-controller-adhoc.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml) | 485 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_OPERATIONS_WEBHOOK` | Ad-hoc Longevity Test controller status |
| 11 | [`zxf-single-day-performance-test-controller-adhoc.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml) | 482 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_OPERATIONS_WEBHOOK`, `secrets.SLACK_CITR_FAILURES_WEBHOOK` | Ad-hoc Performance Test controller status |
| 12 | [`zxf-single-day-performance-test-controller.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-performance-test-controller.yaml) | 405 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_PERFORMANCE_TEST_REPORTS`, `secrets.SLACK_CITR_FAILURES_WEBHOOK` | Scheduled Performance Test controller status |
| 13 | [`zxf-single-day-longevity-test-controller.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-longevity-test-controller.yaml) | 406 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_PERFORMANCE_TEST_REPORTS`, `secrets.SLACK_CITR_FAILURES_WEBHOOK` | Scheduled Longevity Test controller status |
| 14 | [`zxf-merge-queue-performance-test-controller.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-merge-queue-performance-test-controller.yaml) | 293 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_OPERATIONS_WEBHOOK` | Scheduled MQPT controller failure report |
| 15 | [`zxc-mirror-node-regression.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-mirror-node-regression.yaml) | 190 | `cat <<EOF > slack_payload.json` | `secrets.slack-detailed-report-webhook` | Mirror Node Regression status report |
| 16 | [`zxc-block-node-regression.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-block-node-regression.yaml) | 459 | `cat <<EOF > slack_payload.json` | `secrets.slack-detailed-report-webhook` | Block Node Regression status report |
| 17 | [`node-flow-deploy-release-artifact.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-flow-deploy-release-artifact.yaml) | 383 | `cat <<EOF > slack_payload.json` | `secrets.SLACK_CITR_FAILURES_WEBHOOK` | Deploy Production Release failure report |
| 18 | [`node-zxc-build-release-artifact.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-zxc-build-release-artifact.yaml) | 961 | `payload: \|` (YAML Input) | `secrets.slack-webhook-url` | Maven Central/GCP Release notification |
| 19 | [`node-zxcron-release-branching.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-zxcron-release-branching.yaml) | 129 | `payload: \|` (YAML Input) | `secrets.SLACK_RELEASE_WEBHOOK` | Automatic Release Branching notification |
| 20 | [`node-zxcron-release-branching.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-zxcron-release-branching.yaml) | 281 | `payload: \|` (YAML Input) | `secrets.SLACK_RELEASE_WEBHOOK` | Automatic Release Tagging notification |
| 21 | [`zxf-prepare-extended-test-suite.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-prepare-extended-test-suite.yaml) | 168 | `payload: \|` (YAML Input) | `secrets.SLACK_CITR_FAILURES_WEBHOOK` | XTS Candidate Tagging Failure report |

---

## 🔍 Detailed Location Breakdowns & Migration Strategy

Below are detailed views of each location, showing the exact inline payload construction block alongside its suggested `gomplate` refactoring template definition.

### 1. `zxc-json-rpc-relay-regression.yaml` (Line 223)
* **Path:** [`.github/workflows/zxc-json-rpc-relay-regression.yaml#L214-L297`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-json-rpc-relay-regression.yaml#L214-L297)
* **Snippet:**
  ```bash
  COLOR="#FF0000"
  if [[ "${{ needs.json-rpc-relay-regression.result }}" == "success" ]]; then
    COLOR="#00FF00"
  elif [[ "${{ needs.json-rpc-relay-regression.result }}" == "cancelled" ]]; then
    COLOR="#555555"
  fi
  cat <<EOF > slack_payload.json
  {
    "attachments": [
      {
        "color": "${COLOR}",
        "blocks": [
          {
            "type": "header",
            "text": {
              "type": "plain_text",
              "text": ":vertical_traffic_light: Hiero Consensus Node - JSON-RPC Relay Regression Test Report",
              "emoji": true
            }
          },
          ...
  ```
* **Migration Strategy:** 
  1. Extract payload to a new template: `.github/workflows/templates/slack-json-rpc-relay-regression.json.tpl`
  2. Use `gomplate` to compile variables:
     ```bash
     gomplate -f .github/workflows/templates/slack-json-rpc-relay-regression.json.tpl -o slack_payload.json
     ```
  3. Export `COLOR`, `RESULT`, `REF`, and `RUN_ID` as environment variables for `gomplate` interpolation.

---

### 2. `zxc-merge-queue-performance-test.yaml` (Line 938)
* **Path:** [`.github/workflows/zxc-merge-queue-performance-test.yaml#L938-L998`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-merge-queue-performance-test.yaml#L938-L998)
* **Snippet:**
  ```bash
  cat <<EOF > slack_payload.json
  {
    "attachments": [
      {
        "color": "${COLOR}",
        "blocks": [
          {
            "type": "header",
            "text": {
              "type": "plain_text",
              "text": "Merge Queue Performance Test Report (MQPT) - ${{ needs.clean-branch-name.outputs.clean_ref }}",
              "emoji": true
            }
          },
          ...
          {
            "type": "rich_text",
            "elements": [
              {
                "type": "rich_text_preformatted",
                "elements": [
                  ${freport}
                ]
              }
            ]
          },
          ...
  ```
* **Migration Strategy:** 
  1. This is a complex report where `freport` is dynamically generated at runtime using awk/perl to create partial JSON structure.
  2. Extract the overall wrapper structure to `.github/workflows/templates/slack-mqpt-report.json.tpl`.
  3. Pass `freport` (or the raw values) as environment variables to allow clean template rendering.

---

### 3. `zxc-single-day-longevity-test.yaml` (Line 1124)
* **Path:** [`.github/workflows/zxc-single-day-longevity-test.yaml#L1124-L1184`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-single-day-longevity-test.yaml#L1124-L1184)
* **Snippet:**
  Similar to MQPT, generates dynamic test statistics (`freport`), formats color codes based on needs/results, and writes to `slack_payload.json` inline.
* **Migration Strategy:** Extract structure into `.github/workflows/templates/slack-longevity-report.json.tpl` and populate variables via `gomplate`.

---

### 4. `zxc-single-day-performance-test.yaml` (Line 1590)
* **Path:** [`.github/workflows/zxc-single-day-performance-test.yaml#L1590-L1650`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-single-day-performance-test.yaml#L1590-L1650)
* **Snippet:**
  Utilizes the same dynamic `freport` compilation structure to deliver a detailed performance summary of a single-day run.
* **Migration Strategy:** Share or replicate the performance template under `.github/workflows/templates/slack-performance-report.json.tpl`.

---

### 5. `zxc-tck-regression.yaml` (Line 251)
* **Path:** [`.github/workflows/zxc-tck-regression.yaml#L251-L325`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-tck-regression.yaml#L251-L325)
* **Snippet:**
  ```bash
  COLOR="#FF0000"
  if [[ "${{ needs.tck-regression.result }}" == "success" ]]; then
    COLOR="#00FF00"
  elif [[ "${{ needs.tck-regression.result }}" == "cancelled" ]]; then
    COLOR="#555555"
  fi
  cat <<EOF > slack_payload.json
  {
    "attachments": [
      {
        "color": "${COLOR}",
        "blocks": [
          {
            "type": "header",
            "text": {
              "type": "plain_text",
              "text": ":vertical_traffic_light: Hiero Consensus Node - SDK TCK Regression Test Report",
              "emoji": true
            }
          },
          ...
  ```
* **Migration Strategy:** Create `.github/workflows/templates/slack-tck-regression.json.tpl` to handle the simple status details block.

---

### 6, 7 & 8. `zxcron-promote-build-candidate.yaml` (Lines 226, 313, 464)
* **Path:** [`.github/workflows/zxcron-promote-build-candidate.yaml`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxcron-promote-build-candidate.yaml)
* **Snippet:**
  - **Line 226:** Builds status payload for check execution in the promotion flow.
  - **Line 313:** Builds positive confirmation payload for successful promotion and release tagging.
  - **Line 464:** Builds complete run completion payload.
* **Migration Strategy:**
  1. Define `.github/workflows/templates/slack-promote-candidate-check.json.tpl`
  2. Define `.github/workflows/templates/slack-promote-candidate-success.json.tpl`
  3. Define `.github/workflows/templates/slack-promote-candidate-complete.json.tpl`

---

### 9. `zxf-merge-queue-performance-test-controller-adhoc.yaml` (Line 353)
* **Path:** [`.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml#L353-L450`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml#L353-L450)
* **Snippet:**
  Generates ad-hoc MQPT test execution and failure alerts using inline `cat <<EOF`.
* **Migration Strategy:** Convert using standard `.github/workflows/templates/slack-mqpt-adhoc-controller.json.tpl`.

---

### 10 & 13. Longevity Test Controllers (Adhoc: Line 485, Scheduled: Line 406)
* **Paths:**
  - [`.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml#L485`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml#L485)
  - [`.github/workflows/zxf-single-day-longevity-test-controller.yaml#L406`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-longevity-test-controller.yaml#L406)
* **Snippet:**
  Collects test execution, results, and issues, creating a Slack JSON attachment payload with custom parameters.
* **Migration Strategy:** Consolidate both scheduled and ad-hoc controllers to point to a single `slack-longevity-controller.json.tpl` template, using environment flags to dynamically switch headers or target channels.

---

### 11 & 12. Performance Test Controllers (Adhoc: Line 482, Scheduled: Line 405)
* **Paths:**
  - [`.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml#L482`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml#L482)
  - [`.github/workflows/zxf-single-day-performance-test-controller.yaml#L405`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-single-day-performance-test-controller.yaml#L405)
* **Migration Strategy:** Map to a shared `.github/workflows/templates/slack-performance-controller.json.tpl` compiled using `gomplate`.

---

### 14. `zxf-merge-queue-performance-test-controller.yaml` (Line 293)
* **Path:** [`.github/workflows/zxf-merge-queue-performance-test-controller.yaml#L293-L390`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-merge-queue-performance-test-controller.yaml#L293-L390)
* **Snippet:**
  Builds status summaries for scheduled runs.
* **Migration Strategy:** Reuse or inherit the MQPT templates structure under `slack-mqpt-scheduled-controller.json.tpl`.

---

### 15. `zxc-mirror-node-regression.yaml` (Line 190)
* **Path:** [`.github/workflows/zxc-mirror-node-regression.yaml#L190-L264`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-mirror-node-regression.yaml#L190-L264)
* **Snippet:**
  ```bash
  COLOR="#FF0000"
  if [[ "${{ needs.mirror-node-regression.result }}" == "success" ]]; then
    COLOR="#00FF00"
  elif [[ "${{ needs.mirror-node-regression.result }}" == "cancelled" ]]; then
    COLOR="#555555"
  fi
  cat <<EOF > slack_payload.json
  {
    "attachments": [
      {
        "color": "${COLOR}",
        "blocks": [
          {
            "type": "header",
            "text": {
              "type": "plain_text",
              "text": ":vertical_traffic_light: Hiero Consensus Node - Mirror Node Regression Test Report",
              "emoji": true
            }
          },
          ...
  ```
* **Migration Strategy:** Refactor into `.github/workflows/templates/slack-mirror-node-regression.json.tpl` and run `gomplate`.

---

### 16. `zxc-block-node-regression.yaml` (Line 459)
* **Path:** [`.github/workflows/zxc-block-node-regression.yaml#L459-L524`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxc-block-node-regression.yaml#L459-L524)
* **Snippet:**
  Very similar block structures to mirror node regression report but with titles referencing "Block Node Regression".
* **Migration Strategy:** Extract into `.github/workflows/templates/slack-block-node-regression.json.tpl`.

---

### 17. `node-flow-deploy-release-artifact.yaml` (Line 383)
* **Path:** [`.github/workflows/node-flow-deploy-release-artifact.yaml#L383-L484`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-flow-deploy-release-artifact.yaml#L383-L484)
* **Snippet:**
  ```bash
  cat <<EOF > slack_payload.json
  {
    "attachments": [
      {
        "color": "#FF0000",
        "blocks": [
          {
            "type": "header",
            "text": {
              "type": "plain_text",
              "text": ":exclamation: Hiero Consensus Node - Deploy Production Release Failure Report",
              "emoji": true
            }
          },
          ...
  ```
* **Migration Strategy:** Convert this crucial production failure report to a clean `.github/workflows/templates/slack-deploy-release-failure.json.tpl` template.

---

### 18. `node-zxc-build-release-artifact.yaml` (Line 961)
* **Path:** [`.github/workflows/node-zxc-build-release-artifact.yaml#L961-L1057`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-zxc-build-release-artifact.yaml#L961-L1057)
* **Snippet:**
  ```yaml
      - name: Send Slack Notification (Maven Central)
        uses: slackapi/slack-github-action@b0fa283ad8fea605de13dc3f449259339835fc52 # v2.1.0
        with:
          webhook: ${{ secrets.slack-webhook-url }}
          webhook-type: incoming-webhook
          payload-templated: true
          payload: |
            {
              "attachments": [
                {
                  "color": "#b7f350",
                  "blocks": [
                    {
                      "type": "header",
                      ...
  ```
* **Migration Strategy:** This uses YAML's multi-line input string block. It should be refactored by installing `gomplate` prior to this step and rendering a `.github/workflows/templates/slack-build-release-success.json.tpl` template, then using `payload-file-path: slack_payload.json` on the Slack Action step.

---

### 19 & 20. `node-zxcron-release-branching.yaml` (Lines 129, 281)
* **Path:** [`.github/workflows/node-zxcron-release-branching.yaml#L129`, `#L281`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/node-zxcron-release-branching.yaml)
* **Snippet:**
  Utilizes the inline `payload: |` block within the Github Action input to notify about release branch and tag creations.
* **Migration Strategy:** Create `.github/workflows/templates/slack-release-branching.json.tpl` and `.github/workflows/templates/slack-release-tagging.json.tpl`. Install `gomplate` in these jobs and invoke it to output to `slack_payload.json`.

---

### 21. `zxf-prepare-extended-test-suite.yaml` (Line 168)
* **Path:** [`.github/workflows/zxf-prepare-extended-test-suite.yaml#L168-L200`](file:///c:/Users/dhara/Documents/Aashish/code/hiero-consensus-node/.github/workflows/zxf-prepare-extended-test-suite.yaml#L168-L200)
* **Snippet:**
  Uses YAML `payload: |` inline block to publish failure alerts about XTS Candidate Tagging to the failure channel.
* **Migration Strategy:** Refactor to `.github/workflows/templates/slack-xts-candidate-tagging-failure.json.tpl` using the standard `gomplate` compilation step.
