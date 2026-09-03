| File Name                                    | Workflow Name                          | Deprecated File Name                                  | Deprecated Workflow Name                                          |
|----------------------------------------------|----------------------------------------|-------------------------------------------------------|-------------------------------------------------------------------|
| # USER (0-99)                                |                                        |                                                       |                                                                   |
| 000-user-dry-run-mats-suite.yaml             | 000: [USER] CITR MATS Dry Run          | flow-dry-run-mats-suite.yaml                          | [CITR] MATS Dry Run                                               |
| 001-user-dry-run-extended-test-suite.yaml    | 001: [USER] CITR XTS Dry Run           | flow-dry-run-extended-test-suite.yaml                 | [CITR] XTS Dry Run                                                |
| 002-user-deploy-adhoc-artifact.yaml          | 002: [USER] Deploy Adhoc Artifact      | node-flow-deploy-adhoc-artifact.yaml                  | Node: Deploy Adhoc Release                                        |
| 003-user-increment-next-main-release.yaml    | 003: [USER] Increment Main Rel         | flow-increment-next-main-release.yaml                 | [Release] Increment Version File                                  |
| 004-user-deploy-preview.yaml                 | 004: [USER] Deploy Preview             | node-flow-deploy-preview.yaml                         | Node: Deploy Preview                                              |
| 005-user-artifact-determinism.yaml           | 005: [USER] Artifact Determinism       | flow-artifact-determinism.yaml                        | Artifact Determinism                                              |
| 006-user-update-gs-state-variable.yaml       | 006: [USER] Update GS State Var        | zxf-update-gs-state-variable.yaml                     | ZXF: Update GS_STATE Variable                                     |
|                                              |                                        |                                                       |                                                                   |
| # OPERATIONAL (100-199)                      |                                        |                                                       |                                                                   |
| 100-user-collect-workflow-logs.yaml          | 100: [USER] Collect Workflow Logs      | zxf-collect-workflow-logs.yaml                        | ZXF: Collect Workflow Run Logs                                    |
| 101-user-trigger-release.yaml                | 101: [USER] Trigger Release            | flow-trigger-release.yaml                             | [Release] Create New Release                                      |
| 102-user-memory-profile-ctrl.yaml            | 102: [USER] Memory Profile Ctrl        | 050-user-memory-profile-ctrl.yaml                     | 050: [USER] Memory Profile Ctrl                                   |
| 103-user-solo-tests-adhoc.yaml               | 103: [USER] Solo Tests Adhoc           | 200-user-adhoc-solo-tests.yaml                        | 200: [USER] Ad Hoc Solo Tests                                     |
| 104-user-wraps-smoke-test.yaml               | 104: [USER] WRAPS Runner Smoke Test    | N/A                                                   | N/A                                                               |
| 105-user-publish-wraps-key.yaml              | 105: [USER] Publish Wraps Proving Key Image | N/A                                                   | N/A                                                               |
| 106-disp-xts-optional-tests.yaml             | 106: [DISP] XTS Optional Tests         | N/A                                                   | N/A                                                               |
|                                              |                                        |                                                       |                                                                   |
| # CITR (200-299)                             |                                        |                                                       |                                                                   |
| 201-user-sdpt-controller-adhoc.yaml          | 201: [USER] CITR SDPT Ctrl Adhoc       | zxf-single-day-performance-test-controller-adhoc.yaml | ZXF: [CITR] Adhoc - Single Day Performance Test Controller (SDPT) |
| 202-user-sdlt-controller-adhoc.yaml          | 202: [USER] CITR SDLT Ctrl Adhoc       | zxf-single-day-longevity-test-controller-adhoc.yaml   | ZXF: [CITR] Adhoc - Single Day Longevity Test Controller          |
| 203-user-mdlt-controller-adhoc.yaml          | 203: [USER] CITR MDLT Ctrl Adhoc       |                                                       |                                                                   |
| 204-disp-mdlt-monitor.yaml                   | 204: [DISP] CITR MDLT Monitor          |                                                       |                                                                   |
| 205-disp-mdlt-publish-results.yaml           | 205: [DISP] CITR MDLT Publish Results  |                                                       |                                                                   |
| 206-disp-mdlt-tag-result.yaml                | 206: [DISP] CITR MDLT Tag Result       |                                                       |                                                                   |
| 221-disp-sdpt-controller.yaml                | 221: [DISP] CITR SDPT Controller       | zxf-single-day-performance-test-controller.yaml       | ZXF: [CITR] Single Day Performance Test Controller (SDPT)         |
| 222-disp-sdlt-controller.yaml                | 222: [DISP] CITR SDLT Controller       | zxf-single-day-longevity-test-controller.yaml         | ZXF: [CITR] Single Day Longevity Test Controller                  |
| 223-disp-sdct-controller.yaml                | 223: [DISP] CITR SDCT Controller       | zxf-single-day-canonical-test.yaml                    | ZXF: [CITR] Single Day Canonical Test (SDCT)                      |
| 224-disp-mdlt-controller.yaml                | 224: [DISP] CITR MDLT Controller       |                                                       |                                                                   |
| 225-disp-release-chewie-allocation.yaml      | 225: [DISP] Release Chewie Allocation  |                                                       |                                                                   |
|                                              |                                        |                                                       |                                                                   |
| # TRIGGERED (300-399)                        |                                        |                                                       |                                                                   |
| 300-flow-build-application.yaml              | 300: [FLOW] Build Application          | node-flow-build-application.yaml                      | Node: Build Application                                           |
| 301-flow-deploy-release-artifact.yaml        | 301: [FLOW] Deploy Prod Release        | node-flow-deploy-release-artifact.yaml                | ZXF: Deploy Production Release                                    |
| 302-disp-prepare-extended-test-suite.yaml    | 302: [DISP] CITR Prepare XTS           | zxf-prepare-extended-test-suite.yaml                  | ZXF: [CITR] Prepare Extended Test Suite                           |
| 303-disp-deploy-integration.yaml             | 303: [DISP] Deploy Integration         | node-zxf-deploy-integration.yaml                      | ZXF: [Node] Deploy Integration Network Release                    |
| 304-flow-publish-yahcli-image.yaml           | 304: [FLOW] Publish Yahcli Image       | zxf-publish-yahcli-image.yaml                         | ZXC: Publish Yahcli Image                                         |
| 305-flow-generate-release-notes.yaml         | 305: [FLOW] Generate Rel Notes         | flow-generate-release-notes.yaml                      | Generate Release Notes                                            |
| 306-flow-snyk-monitor.yaml                   | 306: [FLOW] Snyk Monitor               | node-zxf-snyk-monitor.yaml                            | ZXF: Snyk Monitor                                                 |
|                                              |                                        |                                                       |                                                                   |
| # RESERVED (400-599)                         |                                        |                                                       |                                                                   |
|                                              |                                        |                                                       |                                                                   |
| # TEST HELPERS (600-699)                     |                                        |                                                       |                                                                   |
| 600-flow-pull-request-checks.yaml            | 600: [FLOW] PR Checks                  | node-flow-pull-request-checks.yaml                    | Node: PR Checks                                                   |
| 601-flow-pull-request-formatting.yaml        | 601: [FLOW] PR Formatting              | flow-pull-request-formatting.yaml                     | PR Formatting                                                     |
|                                              |                                        |                                                       |                                                                   |
| # AI HELPERS (700-799)                       |                                        |                                                       |                                                                   |
| 700-flow-copilot-setup-steps.yaml            | 700: [FLOW] Copilot Setup Steps        | 700-flow-copilot-setup-steps.yaml                     | 700: [FLOW] Copilot Setup Steps                                   |
| 701-flow-auto-unapprove.yaml                 | 701: [FLOW] Auto Unapprove PR          | 080-flow-auto-unapprove.yaml                          | 080: [FLOW] Auto Unapprove PR                                     |
|                                              |                                        |                                                       |                                                                   |
| # REUSABLE (800-899)                         |                                        |                                                       |                                                                   |
| 800-call-mats-tests.yaml                     | 800: [CALL] Exec MATS Tests            | zxc-mats-tests.yaml                                   | ZXC: Executable MATS Tests                                        |
| 801-call-snyk-scan.yaml                      | 801: [CALL] Snyk Scan                  | zxc-snyk-scan.yaml                                    | ZXC: Snyk Scan                                                    |
| 802-call-compile-and-spotless-check.yaml     | 802: [CALL] Compile And Spotless       | zxc-compile-and-spotless-check.yaml                   | ZXC: Compile and Spotless Check                                   |
| 803-call-execute-unit-tests.yaml             | 803: [CALL] Exec Unit Tests            | zxc-execute-unit-tests.yaml                           | ZXC: Execute Unit Tests                                           |
| 804-call-execute-integration-tests.yaml      | 804: [CALL] Exec Integration Tests     | zxc-execute-integration-tests.yaml                    | ZXC: Execute Integration Tests                                    |
| 805-call-execute-hapi-tests.yaml             | Deprecated: 805: [CALL] Exec HAPI Tests | zxc-execute-hapi-tests.yaml                           | ZXC: Execute HAPI Tests                                           |
| 806-call-execute-timing-sensitive-tests.yaml | 806: [CALL] Exec Timing Tests          | zxc-execute-timing-sensitive-tests.yaml               | ZXC: Execute Timing Sensitive Tests                               |
| 807-call-execute-hammer-tests.yaml           | 807: [CALL] Exec Hammer Tests          | zxc-execute-hammer-tests.yaml                         | ZXC: Execute Hammer Tests                                         |
| 808-call-execute-otter-tests.yaml            | Deprecated: 808: [CALL] Exec Otter Tests | zxc-execute-otter-tests.yaml                          | ZXC: Execute Otter Tests                                          |
| 809-call-dependency-module-check.yaml        | 809: [CALL] Dependency Module Chk      | zxc-dependency-module-check.yaml                      | ZXC: Dependency Module Check                                      |
| 810-call-execute-mats-hapi.yaml              | 810: [CALL] Exec MATS HAPI Suites      | N/A                                                   | N/A                                                               |
| 811-call-execute-mats-otter.yaml             | 811: [CALL] Exec MATS Otter Suites     | N/A                                                   | N/A                                                               |
| 815-call-xts-tests.yaml                      | 815: [CALL] Exec XTS Tests             | zxc-xts-tests.yaml                                    | ZXC: Executable XTS Tests                                         |
| 816-call-build-publish-state-validator.yaml  | 816: [CALL] Build State Validator      | zxc-build-publish-state-validator.yaml                | ZXC: Build & Publish Hedera State Validator Uber JAR              |
| 817-call-jrs-regression.yaml                 | 817: [CALL] JRS Regression             | zxc-jrs-regression.yaml                               | ZXC: Regression                                                   |
| 818-call-json-rpc-relay-regression.yaml      | 818: [CALL] JSON-RPC Relay Reg         | zxc-json-rpc-relay-regression.yaml                    | ZXC: JSON-RPC Relay Regression                                    |
| 819-call-tck-regression.yaml                 | 819: [CALL] TCK Regression             | zxc-tck-regression.yaml                               | ZXC: TCK Regression                                               |
| 820-call-mirror-node-regression.yaml         | 820: [CALL] Mirror Node Regress        | zxc-mirror-node-regression.yaml                       | ZXC: Mirror Node Regression                                       |
| 821-call-block-node-regression.yaml          | 821: [CALL] Block Node Regression      | zxc-block-node-regression.yaml                        | ZXC: Block Node Explorer Regression                               |
| 822-call-verify-docker-determinism.yaml      | 822: [CALL] Verify Docker Build        | zxc-verify-docker-build-determinism.yaml              | ZXC: Verify Docker Build Determinism                              |
| 823-call-verify-gradle-determinism.yaml      | 823: [CALL] Verify Gradle Build        | zxc-verify-gradle-build-determinism.yaml              | ZXC: Verify Gradle Build Determinism                              |
| 824-call-xts-optional-tests.yaml             | 824: [CALL] Exec XTS Optional Tests    | N/A                                                   | N/A                                                               |
| 825-call-migration-testing.yaml              | 825: [CALL] Migration Testing          | zxc-migration-testing-yaml                            | ZXC: Migration Testing                                            |
| 826-call-solo-078-to-079-cutover.yaml        | 826: [CALL] Solo 078-079 Cutover       | N/A                                                   | N/A                                                               |
| 827-call-execute-xts-hapi.yaml               | 827: [CALL] Exec XTS HAPI Suites       | N/A                                                   | N/A                                                               |
| 828-call-execute-xts-otter.yaml              | 828: [CALL] Exec XTS Otter Suites      | N/A                                                   | N/A                                                               |
| 831-call-single-day-performance-test.yaml    | 831: [CALL] CITR Exec SDPT             | zxc-single-day-performance-test.yaml                  | ZXC: [CITR] Single Day Performance Test                           |
| 832-call-execute-performance-test.yaml       | 832: [CALL] CITR Exec Perf Test        | zxc-execute-performance-test.yaml                     | ZXC: [CITR] Execute Performance Test                              |
| 833-call-single-day-longevity-test.yaml      | 833: [CALL] CITR Exec SDLT             | zxc-single-day-longevity-test.yaml                    | ZXC: [CITR] Single Day Longevity Test                             |
| 835-call-multi-day-longevity-test.yaml       | 835: [CALL] CITR Exec MDLT             |                                                       |                                                                   |
| 836-call-execute-hapi-misc.yaml              | 836: [CALL] Exec HAPI Misc             | N/A                                                   | N/A                                                               |
| 837-call-execute-hapi-misc-records-crypto.yaml | 837: [CALL] Exec HAPI Rec Crypto       | N/A                                                   | N/A                                                               |
| 838-call-execute-hapi-token-time-consuming.yaml | 838: [CALL] Exec HAPI Token Time       | N/A                                                   | N/A                                                               |
| 839-call-execute-hapi-simple-fees-nd-reconnect.yaml | 839: [CALL] Exec HAPI Fees NDRec       | N/A                                                   | N/A                                                               |
| 840-call-execute-hapi-smart-contract-iss.yaml | 840: [CALL] Exec HAPI SC ISS           | N/A                                                   | N/A                                                               |
| 841-call-execute-hapi-restart.yaml           | 841: [CALL] Exec HAPI Restart          | N/A                                                   | N/A                                                               |
| 842-call-execute-hapi-atomic-batch.yaml      | 842: [CALL] Exec HAPI Atomic Batch     | N/A                                                   | N/A                                                               |
| 843-call-execute-hapi-state-throttling.yaml  | 843: [CALL] Exec HAPI State Throttle   | N/A                                                   | N/A                                                               |
| 844-call-execute-hapi-bn-communication.yaml  | 844: [CALL] Exec HAPI BN Comms         | N/A                                                   | N/A                                                               |
| 845-call-execute-hapi-wraps.yaml             | 845: [CALL] Exec HAPI Wraps            | N/A                                                   | N/A                                                               |
| 846-call-execute-hapi-cutover.yaml           | 846: [CALL] Exec HAPI Cutover          | N/A                                                   | N/A                                                               |
| 847-call-execute-otter-fast.yaml             | 847: [CALL] Exec Fast Otter Tests      | N/A                                                   | N/A                                                               |
| 848-call-execute-otter-full.yaml             | 848: [CALL] Exec Full Otter Tests      | N/A                                                   | N/A                                                               |
| 849-call-execute-otter-chaos.yaml            | 849: [CALL] Exec Chaos Otter Tests     | N/A                                                   | N/A                                                               |
| 850-call-build-release-artifact.yaml         | 850: [CALL] Build Release Art          | node-zxc-build-release-artifact.yaml                  | ZXC: [Node] Deploy Release Artifacts                              |
| 851-call-deploy-preview.yaml                 | 851: [CALL] Deploy Preview             | node-zxc-deploy-preview.yaml                          | ZXC: [Node] Deploy Preview Network Release                        |
| 852-call-publish-production-image.yaml       | 852: [CALL] Publish Prod Image         | zxc-publish-production-image.yaml                     | ZXC: Publish Production Image                                     |
| 853-call-create-github-release.yaml          | 853: [CALL] Create Github Release      | zxc-create-github-release.yaml                        | ZXC: Create Github Release                                        |
| 854-call-extract-jdk-version.yaml            | 854: [CALL] Extract JDK Version        | 802-extract-jdk-version.yaml                          | 802: [CALL] Extract JDK Version                                   |
| 855-call-extract-citr-vars.yaml              | 855: [CALL] Extract CITR Vars          | 855-extract-citr-vars.yaml                            | 855: [CALL] Extract CITR Vars                                     |
| 856-call-solo-ge044.yaml                     | 856: [CALL] Compute solo-ge-0440 Gate  | 857-call-solo-ge044.yaml                              | 857: [CALL] Compute solo-ge-0440 Gate                             |
| 857-call-workflow-unit-tests.yaml            | 857: [CALL] Workflow Unit Tests        |                                                       |                                                                   |
| 858-call-get-chewie-jwt.yaml                 | 858: [CALL] Get Chewie JWT             |                                                       |                                                                   |
| 859-call-create-chewie-request.yaml          | 859: [CALL] Create Chewie Request      |                                                       |                                                                   |
| 860-call-validate-chewie-jwt.yaml            | 860: [CALL] Validate Chewie JWT        |                                                       |                                                                   |
| 861-call-get-test-config.yaml                | 861: [CALL] Get CITR Test Config       |                                                       |                                                                   |
| 862-call-get-chewie-properties.yaml          | 862: [CALL] Get CITR Chewie Properties |                                                       |                                                                   |
|                                              |                                        |                                                       |                                                                   |
| # CRON (900-999)                             |                                        |                                                       |                                                                   |
| 900-cron-extended-test-suite.yaml            | 900: [CRON] CITR Ext Test Suite        | zxcron-extended-test-suite.yaml                       | ZXCron: [CITR] Extended Test Suite                                |
| 901-cron-promote-build-candidate.yaml        | 901: [CRON] CITR Promote Build         | zxcron-promote-build-candidate.yaml                   | ZXCron: [CITR] Promote Build Candidate                            |
| 902-cron-auto-namespace-delete.yaml          | 902: [CRON] Auto Namespace Delete      | zxcron-auto-namespaces-delete.yaml                    | Delete automation Latitude Namespaces                             |
| 903-cron-clean.yaml                          | 903: [CRON] Clean Latitude NS          | zxcron-clean.yaml                                     | CronClean Latitude Namespaces                                     |
| 904-cron-release-branching.yaml              | 904: [CRON] Release Branching          | node-zxcron-release-branching.yaml                    | ZXCron: Automatic Release Branching                               |

## Numbering conventions

- **Ranges** are as marked in the table above: USER (0-99), OPERATIONAL (100-199), CITR (200-299),
  TRIGGERED (300-399), RESERVED (400-599), TEST HELPERS (600-699), AI HELPERS (700-799),
  REUSABLE (800-899), CRON (900-999).

- **Within REUSABLE (800-899)** the sub-blocks are conventional, not enforced: 800-809 MATS driver
  and shared building blocks; 810-814 MATS-specific callees; 815-830 XTS driver, panels and
  XTS-specific callees; 831-833 CITR SDPT/SDLT; 836-849 consumer-neutral per-suite test leaves;
  850-853 release; 854-862 utilities and Chewie. A callee that belongs to one consumer should be
  numbered in that consumer's block; per-suite leaves are neutral and are not.

- **The contiguous space below 850 is now exhausted.** The next batch of leaf workflows starts at
  **870**, on a clean ten boundary. 812-814 and 829-830 are reserved for MATS- and XTS-specific
  callees respectively.

- **`disp`-class operational controllers live in 100-199.** `106-disp-xts-optional-tests.yaml` is
  the first of these; 100-105 are all `user`-class. Machine-dispatched controllers that are not
  CITR test controllers (221/222/223/225) belong here rather than in the CITR range.

- **Deprecated workflows keep their number** and take a `Deprecated:` prefix on `name:`, reduced to
  the standard 21-line `print-deprecated` stub. A number is therefore not unique on disk until you
  check which file is live.

