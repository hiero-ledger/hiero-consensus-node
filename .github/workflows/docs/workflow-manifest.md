|                   Current File Name                   |                       Current Workflow Name                       | Future File Name | Future Workflow Name |
|-------------------------------------------------------|-------------------------------------------------------------------|------------------|----------------------|
| # Cron                                                |                                                                   |                  |                      |
| zxcron-extended-test-suite.yaml                       | ZXCron: [CITR] Extended Test Suite                                |                  |                      |
| zxcron-promote-build-candidate.yaml                   | ZXCron: [CITR] Promote Build Candidate                            |                  |                      |
| node-zxcron-release-branching.yaml                    | ZXCron: Automatic Release Branching                               |                  |                      |
| zxcron-clean.yaml                                     | CronClean Latitude Namespaces                                     |                  |                      |
| zxcron-auto-namespaces-delete.yaml                    | Delete automation Latitude Namespaces                             |                  |                      |
| node-zxcron-main-fsts-regression.yaml                 | ZXCron: [Node] Main JRS Tests                                     |                  |                      |
| node-zxcron-release-fsts-regression.yaml              | ZXCron: [Node] Release JRS Tests                                  |                  |                      |
| platform-zxcron-main-jrs-regression.yaml              | ZXCron: [Platform] Main JRS Regression                            |                  |                      |
| platform-zxcron-release-jrs-regression.yaml           | ZXCron: [Platform] Release JRS Regression                         |                  |                      |
|                                                       |                                                                   |                  |                      |
| # REUSABLE                                            |                                                                   |                  |                      |
| zxc-block-node-regression.yaml                        | ZXC: Block Node Explorer Regression                               |                  |                      |
| zxc-execute-performance-test.yaml                     | ZXC: [CITR] Execute Performance Test                              |                  |                      |
| zxc-jrs-regression.yaml                               | ZXC: Regression                                                   |                  |                      |
| zxc-json-rpc-relay-regression.yaml                    | ZXC: JSON-RPC Relay Regression                                    |                  |                      |
| zxc-mirror-node-regression.yaml                       | ZXC: Mirror Node Regression                                       |                  |                      |
| zxc-publish-production-image.yaml                     | ZXC: Publish Production Image                                     |                  |                      |
| zxc-single-day-longevity-test.yaml                    | ZXC: [CITR] Single Day Longevity Test                             |                  |                      |
| zxc-single-day-performance-test.yaml                  | ZXC: [CITR] Single Day Performance Test                           |                  |                      |
| zxc-tck-regression.yaml                               | ZXC: TCK Regression                                               |                  |                      |
| platform-zxc-launch-jrs-workflow.yaml                 | ZXC: Launch JRS Workflow                                          |                  |                      |
| node-zxc-build-release-artifact.yaml                  | ZXC: [Node] Deploy Release Artifacts                              |                  |                      |
| node-zxc-compile-application-code.yaml                | ZXC: [Node] Compile Application Code                              |                  |                      |
| node-zxc-deploy-preview.yaml                          | ZXC: [Node] Deploy Preview Network Release                        |                  |                      |
|                                                       |                                                                   |                  |                      |
| # CICD                                                |                                                                   |                  |                      |
| zxf-collect-workflow-logs.yaml                        | ZXF: Collect Workflow Run Logs                                    |                  |                      |
| zxf-dry-run-extended-test-suite.yaml                  | ZXF: [CITR] XTS Dry Run                                           |                  |                      |
| zxf-prepare-extended-test-suite.yaml                  | ZXF: [CITR] Prepare Extended Test Suite                           |                  |                      |
| zxf-single-day-canonical-test.yaml                    | ZXF: [CITR] Single Day Canonical Test (SDCT)                      |                  |                      |
| zxf-single-day-longevity-test-controller-adhoc.yaml   | ZXF: [CITR] Adhoc - Single Day Longevity Test Controller          |                  |                      |
| zxf-single-day-longevity-test-controller.yaml         | ZXF: [CITR] Single Day Longevity Test Controller                  |                  |                      |
| zxf-single-day-performance-test-controller-adhoc.yaml | ZXF: [CITR] Adhoc - Single Day Performance Test Controller (SDPT) |                  |                      |
| zxf-single-day-performance-test-controller.yaml       | ZXF: [CITR] Single Day Performance Test Controller (SDPT)         |                  |                      |
|                                                       |                                                                   |                  |                      |
| # BUILD                                               |                                                                   |                  |                      |
| node-flow-build-application.yaml                      | Node: Build Application                                           |                  |                      |
| node-flow-deploy-adhoc-artifact.yaml                  | Node: Deploy Adhoc Release                                        |                  |                      |
| node-flow-deploy-release-artifact.yaml                | ZXF: Deploy Production Release                                    |                  |                      |
|                                                       |                                                                   |                  |                      |
| # DETERMINISM                                         |                                                                   |                  |                      |
| flow-artifact-determinism.yaml                        | Artifact Determinism                                              |                  |                      |
| zxc-verify-docker-build-determinism.yaml              | ZXC: Verify Docker Build Determinism                              |                  |                      |
| zxc-verify-gradle-build-determinism.yaml              | ZXC: Verify Gradle Build Determinism                              |                  |                      |
|                                                       |                                                                   |                  |                      |
| # RELEASE                                             |                                                                   |                  |                      |
| flow-generate-release-notes.yaml                      | Generate Release Notes                                            |                  |                      |
| flow-increment-next-main-release.yaml                 | [Release] Increment Version File                                  |                  |                      |
| flow-trigger-release.yaml                             | [Release] Create New Release                                      |                  |                      |
|                                                       |                                                                   |                  |                      |
| # DEPLOY                                              |                                                                   |                  |                      |
| node-flow-deploy-preview.yaml                         | Node: Deploy Preview                                              |                  |                      |
| node-zxf-deploy-integration.yaml                      | ZXF: [Node] Deploy Integration Network Release                    |                  |                      |
|                                                       |                                                                   |                  |                      |
| # General Testing                                     |                                                                   |                  |                      |
| node-flow-pull-request-checks.yaml                    | Node: PR Checks                                                   |                  |                      |
|                                                       |                                                                   |                  |                      |
| # JRS Testing                                         |                                                                   |                  |                      |
| platform-pull-request-extended-checks.yaml            | Platform: PR Extended Checks                                      |                  |                      |
| node-flow-fsts-custom-regression.yaml                 | Node: FSTS Custom Regression                                      |                  |                      |
| node-flow-fsts-daily-interval-01.yaml                 | ZXF: [Node] FSTS Daily (Interval: 1)                              |                  |                      |
| node-flow-fsts-daily-interval-02.yaml                 | ZXF: [Node] FSTS Daily (Interval: 2)                              |                  |                      |
| node-flow-fsts-daily-interval-03.yaml                 | ZXF: [Node] FSTS Daily (Interval: 3)                              |                  |                      |
| node-flow-fsts-daily-interval-04.yaml                 | ZXF: [Node] FSTS Daily (Interval: 4)                              |                  |                      |
| node-flow-fsts-daily-interval-05.yaml                 | ZXF: [Node] FSTS Daily (Interval: 5)                              |                  |                      |
| node-flow-fsts-daily-interval-06.yaml                 | ZXF: [Node] FSTS Daily (Interval: 6)                              |                  |                      |
| node-flow-fsts-daily-regression.yaml                  | Node: FSTS Daily Regression                                       |                  |                      |
| platform-flow-jrs-custom-regression.yaml              | Platform: JRS Custom Regression                                   |                  |                      |
| platform-flow-jrs-daily-regression.yaml               | Platform: JRS Daily Regression                                    |                  |                      |
| platform-zxf-jrs-daily-interval-01.yaml               | ZXF: [Platform] JRS Daily (Interval: 1)                           |                  |                      |
| platform-zxf-jrs-daily-interval-02.yaml               | ZXF: [Platform] JRS Daily (Interval: 2)                           |                  |                      |
| platform-zxf-jrs-daily-interval-03.yaml               | ZXF: [Platform] JRS Daily (Interval: 3)                           |                  |                      |
| platform-zxf-jrs-daily-interval-04.yaml               | ZXF: [Platform] JRS Daily (Interval: 4)                           |                  |                      |
| platform-zxf-jrs-daily-interval-05.yaml               | ZXF: [Platform] JRS Daily (Interval: 5)                           |                  |                      |
| platform-zxf-jrs-daily-interval-06.yaml               | ZXF: [Platform] JRS Daily (Interval: 6)                           |                  |                      |
|                                                       |                                                                   |                  |                      |
| # QOL                                                 |                                                                   |                  |                      |
| zxf-update-gs-state-variable.yaml                     | ZXF: Update GS_STATE Variable                                     |                  |                      |
| flow-pull-request-formatting.yaml                     | PR Formatting                                                     |                  |                      |
| node-zxf-snyk-monitor.yaml                            | ZXF: Snyk Monitor                                                 |                  |                      |
