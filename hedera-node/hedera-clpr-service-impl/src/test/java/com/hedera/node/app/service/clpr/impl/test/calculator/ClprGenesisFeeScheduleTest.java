// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REDACT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the production CLPR fee values inside the genesis {@code simpleFeesSchedules.json}.
 *
 * <p>Goal: catch any silent drift in the per-op base fee or accidental re-introduction of
 * {@code nodeNetworkFeeExempt} on {@link HederaFunctionality#CLPR_SUBMIT_BUNDLE}. Every CLPR
 * transaction functionality MUST appear in the schedule and MUST charge a flat 1/10¢
 * ({@value #FLAT_CLPR_BASE_FEE_TINYCENTS} tinycents).
 *
 * <p>Structural validity of the JSON is already covered by
 * {@code hedera-file-service-impl}'s {@code FeeScheduleTest.testLoadingFeeScheduleFromJson};
 * this test only asserts CLPR-specific values.
 */
class ClprGenesisFeeScheduleTest {

    /** 1/10th of a cent in tinycents (10^8 tinycents per cent ⇒ 10^7 per 0.1 cent). */
    private static final long FLAT_CLPR_BASE_FEE_TINYCENTS = 10_000_000L;

    /**
     * Relative path from the {@code hedera-clpr-service-impl} module's working directory
     * (where Gradle runs tests) to the genesis fee schedule resource owned by
     * {@code hedera-file-service-impl}.
     */
    private static final Path GENESIS_SCHEDULE_PATH = Path.of(
            "..", "hedera-file-service-impl", "src", "main", "resources", "genesis", "simpleFeesSchedules.json");

    /** Every CLPR transaction functionality that MUST have a 1/10¢ flat fee. */
    private static final EnumSet<HederaFunctionality> CLPR_TRANSACTION_OPS = EnumSet.of(
            CLPR_UPDATE_LEDGER_CONFIGURATION,
            CLPR_REGISTER_CHANNEL,
            CLPR_COMPLETE_CHANNEL,
            CLPR_CLOSE_CHANNEL,
            CLPR_REGISTER_CONNECTOR,
            CLPR_COMPLETE_CONNECTOR,
            CLPR_DEREGISTER_CONNECTOR,
            CLPR_SUBMIT_BUNDLE,
            CLPR_REDACT_MESSAGE);

    @Test
    @DisplayName("Every CLPR transaction op has the flat 1/10¢ base fee in the genesis schedule")
    void everyClprTransactionHasFlatFee() throws Exception {
        final FeeSchedule schedule = loadGenesisSchedule();

        for (final HederaFunctionality functionality : CLPR_TRANSACTION_OPS) {
            final var serviceDef = lookupServiceFee(schedule, functionality);
            assertThat(serviceDef)
                    .as("genesis schedule entry for %s", functionality)
                    .isNotNull();
            assertThat(serviceDef.baseFee())
                    .as("baseFee for %s", functionality)
                    .isEqualTo(FLAT_CLPR_BASE_FEE_TINYCENTS);
            assertThat(serviceDef.nodeNetworkFeeExempt())
                    .as("nodeNetworkFeeExempt for %s — endpoints MUST pay per spec §8.10", functionality)
                    .isFalse();
        }
    }

    private static FeeSchedule loadGenesisSchedule() throws Exception {
        assertThat(GENESIS_SCHEDULE_PATH)
                .as("genesis simpleFeesSchedules.json must exist at the expected sibling-module path")
                .exists();
        try (var in = Files.newInputStream(GENESIS_SCHEDULE_PATH)) {
            return FeeSchedule.JSON.parse(new ReadableStreamingData(in));
        }
    }
}
