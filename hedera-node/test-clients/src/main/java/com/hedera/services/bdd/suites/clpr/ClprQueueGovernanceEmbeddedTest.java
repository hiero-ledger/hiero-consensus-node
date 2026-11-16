// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CLPR_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Exercises expand-only queue governance through the admin HAPI transaction pipeline. */
@Tag(CLPR)
public class ClprQueueGovernanceEmbeddedTest {
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.maxQueueDepthCeiling"})
    final Stream<DynamicTest> queueDepthCanStayOrGrowButCannotShrinkOrExceedCeiling() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                overriding("clpr.maxQueueDepthCeiling", "8"),
                withOpContext((spec, log) -> {
                    final var original = readConfig(spec);
                    // Model a ledger initialized with Q=4 without reducing it through an admin transaction.
                    writeConfig(
                            spec,
                            original.copyBuilder()
                                    .throttles(original.throttlesOrThrow()
                                            .copyBuilder()
                                            .maxQueueDepth(4))
                                    .build());
                    final var initial = pbjToProto(
                            readConfig(spec).throttlesOrThrow(),
                            com.hedera.hapi.node.state.clpr.ClprThrottles.class,
                            ClprThrottles.class);
                    final var sameDepth = initial.toBuilder()
                            .setMaxMessagesPerBundle(initial.getMaxMessagesPerBundle() + 1)
                            .build();
                    final var raised = sameDepth.toBuilder().setMaxQueueDepth(8).build();
                    try {
                        allRunFor(
                                spec,
                                update(sameDepth),
                                assertThrottles(sameDepth),
                                update(raised),
                                assertThrottles(raised));
                        final var beforeRejection = readConfig(spec);
                        allRunFor(
                                spec,
                                clprUpdateLedgerConfiguration()
                                        .throttles(raised.toBuilder()
                                                .setMaxQueueDepth(7)
                                                .build())
                                        .payingWith(GENESIS)
                                        .hasKnownStatus(INVALID_CLPR_CONFIGURATION),
                                clprUpdateLedgerConfiguration()
                                        .throttles(raised.toBuilder()
                                                .setMaxQueueDepth(9)
                                                .build())
                                        .payingWith(GENESIS)
                                        .hasKnownStatus(INVALID_CLPR_CONFIGURATION));
                        assertEquals(
                                beforeRejection, readConfig(spec), "rejection must preserve the entire configuration");

                        // Lowering the growth ceiling must not invalidate already advertised queue credit.
                        final var afterCeilingDrop = raised.toBuilder()
                                .setMaxMessagesPerBundle(raised.getMaxMessagesPerBundle() + 1)
                                .build();
                        allRunFor(
                                spec,
                                overriding("clpr.maxQueueDepthCeiling", "6"),
                                update(afterCeilingDrop),
                                assertThrottles(afterCeilingDrop),
                                clprUpdateLedgerConfiguration()
                                        .throttles(afterCeilingDrop.toBuilder()
                                                .setMaxQueueDepth(9)
                                                .build())
                                        .payingWith(GENESIS)
                                        .hasKnownStatus(INVALID_CLPR_CONFIGURATION),
                                assertThrottles(afterCeilingDrop));
                    } finally {
                        writeConfig(spec, original);
                    }
                }));
    }

    private static SpecOperation update(final ClprThrottles throttles) {
        return clprUpdateLedgerConfiguration().throttles(throttles).payingWith(GENESIS);
    }

    private static SpecOperation assertThrottles(final ClprThrottles expected) {
        return clprGetLedgerConfiguration().exposingConfigTo(actual -> assertEquals(expected, actual.getThrottles()));
    }

    private static ClprLedgerConfiguration readConfig(final HapiSpec spec) {
        return spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID)
                .get();
    }

    private static void writeConfig(final HapiSpec spec, final ClprLedgerConfiguration config) {
        spec.mutateEmbeddedState(state -> state.getWritableStates(ClprService.NAME)
                .<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID)
                .put(config));
    }
}
