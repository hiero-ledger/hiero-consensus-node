// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CLPR_SERVICE_ADDRESS;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Single-node embedded test for the endpoint-manifest construction lifecycle. It exercises the
 * full cold-start pipeline in-process:
 * <ol>
 *   <li>{@code HandleWorkflow}'s startup gate calls {@code ClprEndpointManifestReconciler.openConstructionIfSelfChanged},
 *       which sees this node's endpoint absent from the (empty, genesis-seeded) manifest and submits a
 *       {@code ClprEndpointPublication} via gossip;</li>
 *   <li>{@code ClprEndpointPublicationHandler} opens a construction on that differing publication;</li>
 *   <li>{@code ClprEndpointManifestReconciler.reconcile} fast-closes the single-node construction,
 *       writing the finalized manifest at {@code version >= 2}.</li>
 * </ol>
 *
 * <p>There is no {@code clprGetEndpointManifest} query on this branch, so the test reads the
 * {@code ClprEndpointManifest} singleton directly from embedded state ({@code NEEDS_STATE_ACCESS}).
 *
 * <p>Only cold start is covered here: a cert/port/IP rotation is detected at the node's next startup
 * (the in-memory settled gate), and node add/delete pruning rides the post-upgrade boundary — neither
 * of which is reproducible in a single-node embedded run. Those live in the subprocess suites.
 */
@Tag(CLPR)
public class ClprEndpointManifestConstructionEmbeddedTest {

    // Upper bound on manifest-poll rounds. Each round advances embedded consensus by 500ms, so 120
    // rounds ≈ 60s of simulated time. With the shortened 1s grace period the single-node
    // construction opens and force-closes within a handful of rounds; this ceiling is only a
    // safety net so a regression can't spin forever — the loop breaks as soon as the manifest
    // finalizes (version >= 2 with a non-empty endpoint set).
    private static final int MAX_POLL_ROUNDS = 120;

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = PROPERTY_OVERRIDES)
    @DisplayName("cold start: the node self-publishes and the manifest is derived (version >= 2)")
    final Stream<DynamicTest> coldStartDerivesManifest() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                overriding("clpr.endpointManifestEnabled", "true"),
                // Embedded runs only node 0 of a 4-node network, so the construction can never
                // "fast-close" on all-published — it must time out. Shrink the grace window (default
                // 300s + 2 extensions) so the single-node construction force-closes within the test.
                overriding("clpr.manifestGracePeriod", "1s"),
                overriding("clpr.manifestMaxGraceExtensions", "0"),
                // Embedded CLPR tests share one in-process state. An earlier test may have enabled
                // manifests before installing this shortened grace period, leaving a construction
                // whose original deadline is still several minutes away. Restore the two singleton
                // values this cold-start test owns so its result is independent of class order.
                withOpContext((spec, opLog) -> resetManifestConstructionState(spec)),
                // Fund the self node's account (0.0.3 in embedded) so its gossip-submitted
                // ClprEndpointPublication can be paid for if a fee applies.
                cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                withOpContext((spec, opLog) -> {
                    // Drive consensus rounds so the reconciler runs each round: it self-publishes
                    // (round 1), the handler opens a construction on that publication, and a later
                    // round's reconcile force-closes it once the (shortened) grace period expires.
                    // A tiny transfer per iteration guarantees a round; the tick advances the clock so
                    // the grace deadline is reached. Poll the manifest singleton until it finalizes.
                    ClprEndpointManifest manifest = null;
                    for (int i = 0; i < MAX_POLL_ROUNDS; i++) {
                        allRunFor(
                                spec,
                                cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.98", 1L))
                                        .noLogging()
                                        .deferStatusResolution());
                        spec.embeddedHederaOrThrow().tick(Duration.ofMillis(500));
                        Thread.sleep(5);
                        manifest = readManifest(spec);
                        if (manifest != null
                                && manifest.version() >= 2L
                                && !manifest.endpoints().isEmpty()) {
                            break;
                        }
                    }
                    assertThat(manifest)
                            .as("ClprEndpointManifest singleton should be present")
                            .isNotNull();
                    opLog.info(
                            "Manifest after cold start: version={} endpoints={}",
                            manifest.version(),
                            manifest.endpoints().size());
                    assertThat(manifest.version())
                            .as("manifest advances past the genesis seed (v1) once the node self-publishes")
                            .isGreaterThanOrEqualTo(2L);
                    assertThat(manifest.endpoints())
                            .as("single-node network derives exactly its own endpoint")
                            .hasSize(1);
                    final var self = manifest.endpoints().getFirst();
                    assertThat(self.serviceEndpoint())
                            .as("self endpoint carries a service endpoint (IP + port)")
                            .isNotNull();
                    // mTLS not configured in embedded ⇒ plaintext fallback: empty CA cert.
                    assertThat(self.tlsCertificate())
                            .as("mTLS-off self endpoint advertises an empty certificate")
                            .isEqualTo(Bytes.EMPTY);
                }),
                overriding("clpr.endpointManifestEnabled", "false"));
    }

    private static ClprEndpointManifest readManifest(final HapiSpec spec) {
        return spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID)
                .get();
    }

    private static void resetManifestConstructionState(final HapiSpec spec) {
        final var states = spec.embeddedStateOrThrow().getWritableStates(ClprService.NAME);
        states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID)
                .put(ClprEndpointManifest.newBuilder()
                        .version(1L)
                        .serviceAddress(CLPR_SERVICE_ADDRESS)
                        .build());
        states.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .put(ClprEndpointManifestConstruction.DEFAULT);
        spec.commitEmbeddedState();
    }
}
