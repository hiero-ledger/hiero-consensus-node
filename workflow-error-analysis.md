# Workflow Error Analysis

> Generated on 2026-05-22 — run `npx tsx .github/workflows/support/scripts/generate-error-analysis.ts` to refresh.

---

## Failure Mode Checks

Covers: `set-failure-mode` step definitions, `failure-mode` output declarations,
consumed outputs (`needs.*.outputs.failure-mode`), and `if: failure()` conditions.

| File | Pattern | Detail |
|------|---------|---------|
| [200-user-adhoc-solo-tests.yaml:149](.github/workflows/200-user-adhoc-solo-tests.yaml#L149) | if: failure() check | if: ${{ !cancelled() && failure() }} |
| [200-user-adhoc-solo-tests.yaml:173](.github/workflows/200-user-adhoc-solo-tests.yaml#L173) | if: failure() check | if: ${{ !cancelled() && failure() }} |
| [200-user-adhoc-solo-tests.yaml:287](.github/workflows/200-user-adhoc-solo-tests.yaml#L287) | if: failure() check | if: ${{ !cancelled() && failure() }} |
| [200-user-adhoc-solo-tests.yaml:313](.github/workflows/200-user-adhoc-solo-tests.yaml#L313) | if: failure() check | if: ${{ !cancelled() && failure() }} |
| [node-flow-build-application.yaml:161](.github/workflows/node-flow-build-application.yaml#L161) | consumed output | || [[ "${{ needs.mats-tests.outputs.failure-mode }}" == "workflow" ]]; then |
| [node-flow-build-application.yaml:215](.github/workflows/node-flow-build-application.yaml#L215) | consumed output | echo "MATS tests failed due to a ${{ needs.mats-tests.outputs.failure-mode }} error." |
| [node-flow-pull-request-checks.yaml:155](.github/workflows/node-flow-pull-request-checks.yaml#L155) | consumed output | echo "- **Failure Mode:** ${{ needs.mats-pr-checks.outputs.failure-mode }}" |
| [node-zxc-build-release-artifact.yaml:189](.github/workflows/node-zxc-build-release-artifact.yaml#L189) | if: failure() check | if: ${{ inputs.version-policy == 'specified' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:216](.github/workflows/node-zxc-build-release-artifact.yaml#L216) | if: failure() check | if: ${{ inputs.version-policy == 'specified' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:220](.github/workflows/node-zxc-build-release-artifact.yaml#L220) | if: failure() check | if: ${{ inputs.version-policy != 'specified' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:304](.github/workflows/node-zxc-build-release-artifact.yaml#L304) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:311](.github/workflows/node-zxc-build-release-artifact.yaml#L311) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:390](.github/workflows/node-zxc-build-release-artifact.yaml#L390) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:399](.github/workflows/node-zxc-build-release-artifact.yaml#L399) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.version-policy == 'specified' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:425](.github/workflows/node-zxc-build-release-artifact.yaml#L425) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:460](.github/workflows/node-zxc-build-release-artifact.yaml#L460) | if: failure() check | if: ${{ inputs.dry-run-enabled == true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:465](.github/workflows/node-zxc-build-release-artifact.yaml#L465) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:591](.github/workflows/node-zxc-build-release-artifact.yaml#L591) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:750](.github/workflows/node-zxc-build-release-artifact.yaml#L750) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:870](.github/workflows/node-zxc-build-release-artifact.yaml#L870) | if: failure() check | if: ${{ inputs.version-policy != 'specified' && inputs.dry-run-enabled != true && inputs.release-profile != 'none' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:874](.github/workflows/node-zxc-build-release-artifact.yaml#L874) | if: failure() check | if: ${{ inputs.version-policy == 'specified' && inputs.dry-run-enabled != true && inputs.release-profile != 'none' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:881](.github/workflows/node-zxc-build-release-artifact.yaml#L881) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.version-policy == 'specified' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:896](.github/workflows/node-zxc-build-release-artifact.yaml#L896) | if: failure() check | if: ${{ inputs.version-policy == 'specified' && inputs.dry-run-enabled != true && inputs.release-profile != 'none' && !cancelled() && !failure() }} |
| [node-zxc-build-release-artifact.yaml:929](.github/workflows/node-zxc-build-release-artifact.yaml#L929) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.version-policy == 'specified' && !cancelled() && !failure() }} |
| [node-zxc-deploy-preview.yaml:97](.github/workflows/node-zxc-deploy-preview.yaml#L97) | if: failure() check | if: ${{ inputs.version-policy == 'specified' && !cancelled() && !failure() }} |
| [node-zxc-deploy-preview.yaml:111](.github/workflows/node-zxc-deploy-preview.yaml#L111) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-deploy-preview.yaml:118](.github/workflows/node-zxc-deploy-preview.yaml#L118) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-deploy-preview.yaml:121](.github/workflows/node-zxc-deploy-preview.yaml#L121) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [node-zxc-deploy-preview.yaml:136](.github/workflows/node-zxc-deploy-preview.yaml#L136) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [zxc-compile-and-spotless-check.yaml:36](.github/workflows/zxc-compile-and-spotless-check.yaml#L36) | output definition | failure-mode: |
| [zxc-compile-and-spotless-check.yaml:63](.github/workflows/zxc-compile-and-spotless-check.yaml#L63) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-compile-and-spotless-check.yaml:92](.github/workflows/zxc-compile-and-spotless-check.yaml#L92) | set-failure-mode step |  |
| [zxc-dependency-module-check.yaml:35](.github/workflows/zxc-dependency-module-check.yaml#L35) | output definition | failure-mode: |
| [zxc-dependency-module-check.yaml:62](.github/workflows/zxc-dependency-module-check.yaml#L62) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-dependency-module-check.yaml:86](.github/workflows/zxc-dependency-module-check.yaml#L86) | set-failure-mode step |  |
| [zxc-execute-hammer-tests.yaml:36](.github/workflows/zxc-execute-hammer-tests.yaml#L36) | output definition | failure-mode: |
| [zxc-execute-hammer-tests.yaml:63](.github/workflows/zxc-execute-hammer-tests.yaml#L63) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hammer-tests.yaml:104](.github/workflows/zxc-execute-hammer-tests.yaml#L104) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:86](.github/workflows/zxc-execute-hapi-tests.yaml#L86) | output definition | failure-mode: |
| [zxc-execute-hapi-tests.yaml:114](.github/workflows/zxc-execute-hapi-tests.yaml#L114) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:215](.github/workflows/zxc-execute-hapi-tests.yaml#L215) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:232](.github/workflows/zxc-execute-hapi-tests.yaml#L232) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:319](.github/workflows/zxc-execute-hapi-tests.yaml#L319) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:336](.github/workflows/zxc-execute-hapi-tests.yaml#L336) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:421](.github/workflows/zxc-execute-hapi-tests.yaml#L421) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:438](.github/workflows/zxc-execute-hapi-tests.yaml#L438) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:523](.github/workflows/zxc-execute-hapi-tests.yaml#L523) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:540](.github/workflows/zxc-execute-hapi-tests.yaml#L540) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:624](.github/workflows/zxc-execute-hapi-tests.yaml#L624) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:641](.github/workflows/zxc-execute-hapi-tests.yaml#L641) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:708](.github/workflows/zxc-execute-hapi-tests.yaml#L708) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:725](.github/workflows/zxc-execute-hapi-tests.yaml#L725) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:790](.github/workflows/zxc-execute-hapi-tests.yaml#L790) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:807](.github/workflows/zxc-execute-hapi-tests.yaml#L807) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:891](.github/workflows/zxc-execute-hapi-tests.yaml#L891) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:908](.github/workflows/zxc-execute-hapi-tests.yaml#L908) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:975](.github/workflows/zxc-execute-hapi-tests.yaml#L975) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:1002](.github/workflows/zxc-execute-hapi-tests.yaml#L1002) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-hapi-tests.yaml:1008](.github/workflows/zxc-execute-hapi-tests.yaml#L1008) | set-failure-mode step |  |
| [zxc-execute-hapi-tests.yaml:1025](.github/workflows/zxc-execute-hapi-tests.yaml#L1025) | consumed output | "${{ needs.hapi-tests-misc.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1026](.github/workflows/zxc-execute-hapi-tests.yaml#L1026) | consumed output | "${{ needs.hapi-tests-misc-records-crypto-and-serial.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1027](.github/workflows/zxc-execute-hapi-tests.yaml#L1027) | consumed output | "${{ needs.hapi-tests-token-and-time-consuming.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1028](.github/workflows/zxc-execute-hapi-tests.yaml#L1028) | consumed output | "${{ needs.hapi-tests-simple-fees-and-nd-reconnect.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1029](.github/workflows/zxc-execute-hapi-tests.yaml#L1029) | consumed output | "${{ needs.hapi-tests-atomic-batch.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1030](.github/workflows/zxc-execute-hapi-tests.yaml#L1030) | consumed output | "${{ needs.hapi-tests-smart-contracts-and-iss.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1031](.github/workflows/zxc-execute-hapi-tests.yaml#L1031) | consumed output | "${{ needs.hapi-tests-restart.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1032](.github/workflows/zxc-execute-hapi-tests.yaml#L1032) | consumed output | "${{ needs.hapi-tests-bn-comms.outputs.failure-mode || 'none' }}" |
| [zxc-execute-hapi-tests.yaml:1033](.github/workflows/zxc-execute-hapi-tests.yaml#L1033) | consumed output | "${{ needs.hapi-tests-state-throttling.outputs.failure-mode || 'none' }}" |
| [zxc-execute-integration-tests.yaml:41](.github/workflows/zxc-execute-integration-tests.yaml#L41) | output definition | failure-mode: |
| [zxc-execute-integration-tests.yaml:68](.github/workflows/zxc-execute-integration-tests.yaml#L68) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-integration-tests.yaml:127](.github/workflows/zxc-execute-integration-tests.yaml#L127) | set-failure-mode step |  |
| [zxc-execute-otter-tests.yaml:55](.github/workflows/zxc-execute-otter-tests.yaml#L55) | output definition | failure-mode: |
| [zxc-execute-otter-tests.yaml:83](.github/workflows/zxc-execute-otter-tests.yaml#L83) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-otter-tests.yaml:135](.github/workflows/zxc-execute-otter-tests.yaml#L135) | set-failure-mode step |  |
| [zxc-execute-otter-tests.yaml:152](.github/workflows/zxc-execute-otter-tests.yaml#L152) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-otter-tests.yaml:212](.github/workflows/zxc-execute-otter-tests.yaml#L212) | set-failure-mode step |  |
| [zxc-execute-otter-tests.yaml:229](.github/workflows/zxc-execute-otter-tests.yaml#L229) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-otter-tests.yaml:289](.github/workflows/zxc-execute-otter-tests.yaml#L289) | set-failure-mode step |  |
| [zxc-execute-otter-tests.yaml:310](.github/workflows/zxc-execute-otter-tests.yaml#L310) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-otter-tests.yaml:316](.github/workflows/zxc-execute-otter-tests.yaml#L316) | set-failure-mode step |  |
| [zxc-execute-otter-tests.yaml:325](.github/workflows/zxc-execute-otter-tests.yaml#L325) | consumed output | "${{ needs.full-otter.outputs.failure-mode || 'none' }}" |
| [zxc-execute-otter-tests.yaml:326](.github/workflows/zxc-execute-otter-tests.yaml#L326) | consumed output | "${{ needs.fast-otter.outputs.failure-mode || 'none' }}" |
| [zxc-execute-otter-tests.yaml:327](.github/workflows/zxc-execute-otter-tests.yaml#L327) | consumed output | "${{ needs.chaos-otter.outputs.failure-mode || 'none' }}" |
| [zxc-execute-timing-sensitive-tests.yaml:36](.github/workflows/zxc-execute-timing-sensitive-tests.yaml#L36) | output definition | failure-mode: |
| [zxc-execute-timing-sensitive-tests.yaml:63](.github/workflows/zxc-execute-timing-sensitive-tests.yaml#L63) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-timing-sensitive-tests.yaml:104](.github/workflows/zxc-execute-timing-sensitive-tests.yaml#L104) | set-failure-mode step |  |
| [zxc-execute-unit-tests.yaml:42](.github/workflows/zxc-execute-unit-tests.yaml#L42) | output definition | failure-mode: |
| [zxc-execute-unit-tests.yaml:69](.github/workflows/zxc-execute-unit-tests.yaml#L69) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-execute-unit-tests.yaml:131](.github/workflows/zxc-execute-unit-tests.yaml#L131) | set-failure-mode step |  |
| [zxc-mats-tests.yaml:114](.github/workflows/zxc-mats-tests.yaml#L114) | output definition | failure-mode: |
| [zxc-mats-tests.yaml:315](.github/workflows/zxc-mats-tests.yaml#L315) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-mats-tests.yaml:334](.github/workflows/zxc-mats-tests.yaml#L334) | set-failure-mode step |  |
| [zxc-mats-tests.yaml:349](.github/workflows/zxc-mats-tests.yaml#L349) | consumed output | ${{ needs.commit-info.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:350](.github/workflows/zxc-mats-tests.yaml#L350) | consumed output | ${{ needs.compile-and-spotless.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:351](.github/workflows/zxc-mats-tests.yaml#L351) | consumed output | ${{ needs.dependency-check.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:352](.github/workflows/zxc-mats-tests.yaml#L352) | consumed output | ${{ needs.mats-unit-tests.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:353](.github/workflows/zxc-mats-tests.yaml#L353) | consumed output | ${{ needs.mats-integration-tests.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:354](.github/workflows/zxc-mats-tests.yaml#L354) | consumed output | ${{ needs.mats-hapi-tests.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:355](.github/workflows/zxc-mats-tests.yaml#L355) | consumed output | ${{ needs.mats-otter-tests.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:356](.github/workflows/zxc-mats-tests.yaml#L356) | consumed output | ${{ needs.mats-snyk-scan.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:357](.github/workflows/zxc-mats-tests.yaml#L357) | consumed output | ${{ needs.mats-gradle-determinism.outputs.failure-mode || 'none' }} |
| [zxc-mats-tests.yaml:358](.github/workflows/zxc-mats-tests.yaml#L358) | consumed output | ${{ needs.mats-docker-determinism.outputs.failure-mode || 'none' }} |
| [zxc-merge-queue-performance-test.yaml:232](.github/workflows/zxc-merge-queue-performance-test.yaml#L232) | if: failure() check | if: failure() |
| [zxc-publish-production-image.yaml:99](.github/workflows/zxc-publish-production-image.yaml#L99) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.registry-name == 'gcp' && !cancelled() && !failure() }} |
| [zxc-publish-production-image.yaml:107](.github/workflows/zxc-publish-production-image.yaml#L107) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.registry-name == 'jfrog' && !cancelled() && !failure() }} |
| [zxc-publish-production-image.yaml:113](.github/workflows/zxc-publish-production-image.yaml#L113) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.registry-name == 'jfrog' && !cancelled() && !failure() }} |
| [zxc-publish-production-image.yaml:117](.github/workflows/zxc-publish-production-image.yaml#L117) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.registry-name == 'jfrog' && !cancelled() && !failure() }} |
| [zxc-publish-production-image.yaml:158](.github/workflows/zxc-publish-production-image.yaml#L158) | if: failure() check | if: ${{ inputs.dry-run-enabled == true && !cancelled() && !failure() }} |
| [zxc-publish-production-image.yaml:163](.github/workflows/zxc-publish-production-image.yaml#L163) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.registry-name == 'gcp' && !cancelled() && !failure() }} |
| [zxc-publish-production-image.yaml:171](.github/workflows/zxc-publish-production-image.yaml#L171) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && inputs.registry-name == 'jfrog' && !cancelled() && !failure() }} |
| [zxc-single-day-longevity-test.yaml:219](.github/workflows/zxc-single-day-longevity-test.yaml#L219) | if: failure() check | if: failure() |
| [zxc-single-day-performance-test.yaml:245](.github/workflows/zxc-single-day-performance-test.yaml#L245) | if: failure() check | if: failure() |
| [zxc-snyk-scan.yaml:39](.github/workflows/zxc-snyk-scan.yaml#L39) | output definition | failure-mode: |
| [zxc-snyk-scan.yaml:66](.github/workflows/zxc-snyk-scan.yaml#L66) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-snyk-scan.yaml:142](.github/workflows/zxc-snyk-scan.yaml#L142) | set-failure-mode step |  |
| [zxc-verify-docker-build-determinism.yaml:32](.github/workflows/zxc-verify-docker-build-determinism.yaml#L32) | output definition | failure-mode: |
| [zxc-verify-docker-build-determinism.yaml:65](.github/workflows/zxc-verify-docker-build-determinism.yaml#L65) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-docker-build-determinism.yaml:117](.github/workflows/zxc-verify-docker-build-determinism.yaml#L117) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:124](.github/workflows/zxc-verify-docker-build-determinism.yaml#L124) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:128](.github/workflows/zxc-verify-docker-build-determinism.yaml#L128) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:137](.github/workflows/zxc-verify-docker-build-determinism.yaml#L137) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:141](.github/workflows/zxc-verify-docker-build-determinism.yaml#L141) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:145](.github/workflows/zxc-verify-docker-build-determinism.yaml#L145) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:149](.github/workflows/zxc-verify-docker-build-determinism.yaml#L149) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:153](.github/workflows/zxc-verify-docker-build-determinism.yaml#L153) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:166](.github/workflows/zxc-verify-docker-build-determinism.yaml#L166) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:174](.github/workflows/zxc-verify-docker-build-determinism.yaml#L174) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:188](.github/workflows/zxc-verify-docker-build-determinism.yaml#L188) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:192](.github/workflows/zxc-verify-docker-build-determinism.yaml#L192) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:201](.github/workflows/zxc-verify-docker-build-determinism.yaml#L201) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:205](.github/workflows/zxc-verify-docker-build-determinism.yaml#L205) | set-failure-mode step |  |
| [zxc-verify-docker-build-determinism.yaml:235](.github/workflows/zxc-verify-docker-build-determinism.yaml#L235) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-docker-build-determinism.yaml:415](.github/workflows/zxc-verify-docker-build-determinism.yaml#L415) | if: failure() check | if: ${{ steps.regen-manifest.conclusion == 'success' && failure() && !cancelled() }} |
| [zxc-verify-docker-build-determinism.yaml:421](.github/workflows/zxc-verify-docker-build-determinism.yaml#L421) | set-failure-mode step |  |
| [zxc-verify-docker-build-determinism.yaml:440](.github/workflows/zxc-verify-docker-build-determinism.yaml#L440) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-docker-build-determinism.yaml:448](.github/workflows/zxc-verify-docker-build-determinism.yaml#L448) | set-failure-mode step |  |
| [zxc-verify-docker-build-determinism.yaml:456](.github/workflows/zxc-verify-docker-build-determinism.yaml#L456) | consumed output | "${{ needs.generate-baseline.outputs.failure-mode || 'none' }}" |
| [zxc-verify-docker-build-determinism.yaml:457](.github/workflows/zxc-verify-docker-build-determinism.yaml#L457) | consumed output | "${{ needs.verify-artifacts.outputs.failure-mode || 'none' }}" |
| [zxc-verify-gradle-build-determinism.yaml:32](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L32) | output definition | failure-mode: |
| [zxc-verify-gradle-build-determinism.yaml:60](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L60) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-gradle-build-determinism.yaml:109](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L109) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-gradle-build-determinism.yaml:116](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L116) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-gradle-build-determinism.yaml:120](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L120) | if: failure() check | if: ${{ steps.baseline.outputs.exists == 'false' && !failure() && !cancelled() }} |
| [zxc-verify-gradle-build-determinism.yaml:124](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L124) | set-failure-mode step |  |
| [zxc-verify-gradle-build-determinism.yaml:138](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L138) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-gradle-build-determinism.yaml:187](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L187) | set-failure-mode step |  |
| [zxc-verify-gradle-build-determinism.yaml:206](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L206) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-gradle-build-determinism.yaml:304](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L304) | if: failure() check | if: ${{ steps.regen-manifest.conclusion == 'success' && failure() && !cancelled() }} |
| [zxc-verify-gradle-build-determinism.yaml:310](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L310) | set-failure-mode step |  |
| [zxc-verify-gradle-build-determinism.yaml:330](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L330) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-verify-gradle-build-determinism.yaml:338](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L338) | set-failure-mode step |  |
| [zxc-verify-gradle-build-determinism.yaml:347](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L347) | consumed output | "${{ needs.generate-baseline.outputs.failure-mode || 'none' }}" |
| [zxc-verify-gradle-build-determinism.yaml:348](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L348) | consumed output | "${{ needs.generate-matrix.outputs.failure-mode || 'none' }}" |
| [zxc-verify-gradle-build-determinism.yaml:349](.github/workflows/zxc-verify-gradle-build-determinism.yaml#L349) | consumed output | "${{ needs.verify-artifacts.outputs.failure-mode || 'none' }}" |
| [zxc-xts-tests.yaml:125](.github/workflows/zxc-xts-tests.yaml#L125) | output definition | failure-mode: |
| [zxc-xts-tests.yaml:367](.github/workflows/zxc-xts-tests.yaml#L367) | output definition | failure-mode: ${{ steps.set-failure-mode.outputs.failure-mode }} |
| [zxc-xts-tests.yaml:387](.github/workflows/zxc-xts-tests.yaml#L387) | set-failure-mode step |  |
| [zxc-xts-tests.yaml:398](.github/workflows/zxc-xts-tests.yaml#L398) | consumed output | ${{ needs.commit-info.outputs.failure-mode || 'none' }} |
| [zxc-xts-tests.yaml:399](.github/workflows/zxc-xts-tests.yaml#L399) | consumed output | ${{ needs.build.outputs.failure-mode || 'none' }} |
| [zxc-xts-tests.yaml:400](.github/workflows/zxc-xts-tests.yaml#L400) | consumed output | ${{ needs.xts-timing-sensitive-tests.outputs.failure-mode || 'none' }} |
| [zxc-xts-tests.yaml:401](.github/workflows/zxc-xts-tests.yaml#L401) | consumed output | ${{ needs.xts-hammer-tests.outputs.failure-mode || 'none' }} |
| [zxc-xts-tests.yaml:402](.github/workflows/zxc-xts-tests.yaml#L402) | consumed output | ${{ needs.xts-hapi-tests.outputs.failure-mode || 'none' }} |
| [zxc-xts-tests.yaml:403](.github/workflows/zxc-xts-tests.yaml#L403) | consumed output | ${{ needs.xts-otter-tests.outputs.failure-mode || 'none' }} |
| [zxcron-extended-test-suite.yaml:468](.github/workflows/zxcron-extended-test-suite.yaml#L468) | consumed output | || [[ "${{ needs.xts-execution.outputs.failure-mode }}" == "workflow" ]]; then |
| [zxcron-extended-test-suite.yaml:538](.github/workflows/zxcron-extended-test-suite.yaml#L538) | consumed output | FAILURE_MODE="${{ needs.xts-execution.outputs.failure-mode }}" |
| [zxf-publish-yahcli-image.yaml:109](.github/workflows/zxf-publish-yahcli-image.yaml#L109) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |
| [zxf-publish-yahcli-image.yaml:141](.github/workflows/zxf-publish-yahcli-image.yaml#L141) | if: failure() check | if: ${{ inputs.dry-run-enabled == true && !cancelled() && !failure() }} |
| [zxf-publish-yahcli-image.yaml:146](.github/workflows/zxf-publish-yahcli-image.yaml#L146) | if: failure() check | if: ${{ inputs.dry-run-enabled != true && !cancelled() && !failure() }} |

---

## Slack Notifications

Every `slackapi/slack-github-action` invocation across all workflows.

| File | Pattern | Webhook / Secret |
|------|---------|---------|
| [node-flow-build-application.yaml:302](.github/workflows/node-flow-build-application.yaml#L302) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [node-flow-build-application.yaml:311](.github/workflows/node-flow-build-application.yaml#L311) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [node-flow-build-application.yaml:320](.github/workflows/node-flow-build-application.yaml#L320) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [node-flow-build-application.yaml:329](.github/workflows/node-flow-build-application.yaml#L329) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [node-flow-build-application.yaml:338](.github/workflows/node-flow-build-application.yaml#L338) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [node-flow-deploy-release-artifact.yaml:467](.github/workflows/node-flow-deploy-release-artifact.yaml#L467) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [node-zxc-build-release-artifact.yaml:954](.github/workflows/node-zxc-build-release-artifact.yaml#L954) | send notification | {{ secrets.slack-webhook-url }} |
| [node-zxcron-release-branching.yaml:124](.github/workflows/node-zxcron-release-branching.yaml#L124) | commit | {{ secrets.SLACK_RELEASE_WEBHOOK }} |
| [node-zxcron-release-branching.yaml:279](.github/workflows/node-zxcron-release-branching.yaml#L279) | commit | {{ secrets.SLACK_RELEASE_WEBHOOK }} |
| [zxc-block-node-regression.yaml:529](.github/workflows/zxc-block-node-regression.yaml#L529) | send notification | {{ secrets.slack-detailed-report-webhook }} |
| [zxc-json-rpc-relay-regression.yaml:294](.github/workflows/zxc-json-rpc-relay-regression.yaml#L294) | send notification | {{ secrets.slack-detailed-report-webhook }} |
| [zxc-merge-queue-performance-test.yaml:1003](.github/workflows/zxc-merge-queue-performance-test.yaml#L1003) | send notification | {{ secrets.slack-report-webhook }} |
| [zxc-mirror-node-regression.yaml:265](.github/workflows/zxc-mirror-node-regression.yaml#L265) | send notification | {{ secrets.slack-detailed-report-webhook }} |
| [zxc-single-day-longevity-test.yaml:1184](.github/workflows/zxc-single-day-longevity-test.yaml#L1184) | send notification | {{ secrets.slack-report-webhook }} |
| [zxc-single-day-performance-test.yaml:1661](.github/workflows/zxc-single-day-performance-test.yaml#L1661) | send notification | {{ secrets.slack-report-webhook }} |
| [zxc-tck-regression.yaml:324](.github/workflows/zxc-tck-regression.yaml#L324) | send notification | {{ secrets.slack-detailed-report-webhook }} |
| [zxcron-extended-test-suite.yaml:433](.github/workflows/zxcron-extended-test-suite.yaml#L433) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxcron-extended-test-suite.yaml:639](.github/workflows/zxcron-extended-test-suite.yaml#L639) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxcron-extended-test-suite.yaml:648](.github/workflows/zxcron-extended-test-suite.yaml#L648) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxcron-extended-test-suite.yaml:657](.github/workflows/zxcron-extended-test-suite.yaml#L657) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [zxcron-extended-test-suite.yaml:666](.github/workflows/zxcron-extended-test-suite.yaml#L666) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxcron-extended-test-suite.yaml:675](.github/workflows/zxcron-extended-test-suite.yaml#L675) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [zxcron-promote-build-candidate.yaml:287](.github/workflows/zxcron-promote-build-candidate.yaml#L287) | send notification | {{ secrets.SLACK_CITR_BUILD_PROMOTION_WEBHOOK }} |
| [zxcron-promote-build-candidate.yaml:378](.github/workflows/zxcron-promote-build-candidate.yaml#L378) | send notification | {{ secrets.SLACK_CITR_BUILD_PROMOTION_WEBHOOK }} |
| [zxcron-promote-build-candidate.yaml:386](.github/workflows/zxcron-promote-build-candidate.yaml#L386) | send notification | {{ secrets.SLACK_RELEASE_TEAM_WEBHOOK }} |
| [zxcron-promote-build-candidate.yaml:537](.github/workflows/zxcron-promote-build-candidate.yaml#L537) | send notification | {{ secrets.SLACK_CITR_BUILD_PROMOTION_WEBHOOK }} |
| [zxcron-promote-build-candidate.yaml:545](.github/workflows/zxcron-promote-build-candidate.yaml#L545) | send notification | {{ secrets.SLACK_RELEASE_TEAM_WEBHOOK }} |
| [zxf-merge-queue-performance-test-controller-adhoc.yaml:170](.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml#L170) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxf-merge-queue-performance-test-controller-adhoc.yaml:459](.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml#L459) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxf-merge-queue-performance-test-controller.yaml:107](.github/workflows/zxf-merge-queue-performance-test-controller.yaml#L107) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxf-merge-queue-performance-test-controller.yaml:399](.github/workflows/zxf-merge-queue-performance-test-controller.yaml#L399) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxf-prepare-extended-test-suite.yaml:162](.github/workflows/zxf-prepare-extended-test-suite.yaml#L162) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [zxf-single-day-longevity-test-controller-adhoc.yaml:272](.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml#L272) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxf-single-day-longevity-test-controller-adhoc.yaml:601](.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml#L601) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [zxf-single-day-longevity-test-controller.yaml:191](.github/workflows/zxf-single-day-longevity-test-controller.yaml#L191) | send notification | {{ secrets.SLACK_CITR_PERFORMANCE_TEST_REPORTS }} |
| [zxf-single-day-longevity-test-controller.yaml:523](.github/workflows/zxf-single-day-longevity-test-controller.yaml#L523) | send notification | {{ secrets.SLACK_CITR_PERFORMANCE_TEST_REPORTS }} |
| [zxf-single-day-longevity-test-controller.yaml:532](.github/workflows/zxf-single-day-longevity-test-controller.yaml#L532) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [zxf-single-day-performance-test-controller-adhoc.yaml:269](.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml#L269) | send notification | {{ secrets.SLACK_CITR_OPERATIONS_WEBHOOK }} |
| [zxf-single-day-performance-test-controller-adhoc.yaml:598](.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml#L598) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |
| [zxf-single-day-performance-test-controller.yaml:190](.github/workflows/zxf-single-day-performance-test-controller.yaml#L190) | send notification | {{ secrets.SLACK_CITR_PERFORMANCE_TEST_REPORTS }} |
| [zxf-single-day-performance-test-controller.yaml:522](.github/workflows/zxf-single-day-performance-test-controller.yaml#L522) | send notification | {{ secrets.SLACK_CITR_PERFORMANCE_TEST_REPORTS }} |
| [zxf-single-day-performance-test-controller.yaml:531](.github/workflows/zxf-single-day-performance-test-controller.yaml#L531) | send notification | {{ secrets.SLACK_CITR_FAILURES_WEBHOOK }} |

---

## Rootly Notifications

Every `pandaswhocode/rootly-alert-action` invocation across all workflows.

| File | Pattern | Service |
|------|---------|---------|
| [node-flow-build-application.yaml:239](.github/workflows/node-flow-build-application.yaml#L239) | create-rootly-alert |  |
| [node-flow-deploy-release-artifact.yaml:357](.github/workflows/node-flow-deploy-release-artifact.yaml#L357) | create-rootly-alert |  |
| [zxcron-extended-test-suite.yaml:569](.github/workflows/zxcron-extended-test-suite.yaml#L569) | create-rootly-alert |  |
| [zxcron-promote-build-candidate.yaml:443](.github/workflows/zxcron-promote-build-candidate.yaml#L443) | create alert |  |
| [zxf-merge-queue-performance-test-controller-adhoc.yaml:341](.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml#L341) | create alert |  |
| [zxf-merge-queue-performance-test-controller.yaml:281](.github/workflows/zxf-merge-queue-performance-test-controller.yaml#L281) | create-rootly-alert |  |
| [zxf-prepare-extended-test-suite.yaml:145](.github/workflows/zxf-prepare-extended-test-suite.yaml#L145) | Report Failures |  |
| [zxf-single-day-canonical-test.yaml:348](.github/workflows/zxf-single-day-canonical-test.yaml#L348) | create-rootly-alert |  |
| [zxf-single-day-longevity-test-controller-adhoc.yaml:467](.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml#L467) | create alert |  |
| [zxf-single-day-longevity-test-controller.yaml:388](.github/workflows/zxf-single-day-longevity-test-controller.yaml#L388) | create-rootly-alert |  |
| [zxf-single-day-performance-test-controller-adhoc.yaml:464](.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml#L464) | create alert |  |
| [zxf-single-day-performance-test-controller.yaml:387](.github/workflows/zxf-single-day-performance-test-controller.yaml#L387) | create-rootly-alert |  |

