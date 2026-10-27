// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_EVICT_PEER_ENDPOINT;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the production CLPR prices inside the genesis {@code simpleFeesSchedules.json}, and checks that the testnet and
 * previewnet upgrade schedules — which replace the genesis schedule after an upgrade — carry the same CLPR entries.
 *
 * <p>The prices are the draft CLPR fee proposal's: $100 to register or complete a Channel, $10 / $25 / $10 to register,
 * complete, or deregister a Connector, $1 for the admin operations (including evicting a peer endpoint), and $0.01 plus bundle bytes for a
 * bundle. Every CLPR transaction is charged node and network fees ({@code nodeNetworkFeeExempt} is false, spec §8.10).
 *
 * <p>Structural validity of the JSON is already covered by {@code hedera-file-service-impl}'s
 * {@code FeeScheduleTest.testLoadingFeeScheduleFromJson}; this test only asserts CLPR-specific values.
 */
class ClprGenesisFeeScheduleTest {

    /** Tinycents in one US dollar. */
    private static final long USD = 10_000_000_000L;

    /** Path from the {@code hedera-clpr-service-impl} module's working directory to {@code hedera-node}. */
    private static final Path HEDERA_NODE = Path.of("..");

    private static final Path GENESIS_SCHEDULE_PATH = HEDERA_NODE.resolve(
            Path.of("hedera-file-service-impl", "src", "main", "resources", "genesis", "simpleFeesSchedules.json"));

    /** The base fee, in tinycents, of every CLPR transaction. */
    private static final Map<HederaFunctionality, Long> BASE_FEES = Map.of(
            CLPR_UPDATE_LEDGER_CONFIGURATION, USD,
            CLPR_REGISTER_CHANNEL, 100 * USD,
            CLPR_COMPLETE_CHANNEL, 100 * USD,
            CLPR_CLOSE_CHANNEL, USD,
            CLPR_EVICT_PEER_ENDPOINT, USD,
            CLPR_REGISTER_CONNECTOR, 10 * USD,
            CLPR_COMPLETE_CONNECTOR, 25 * USD,
            CLPR_DEREGISTER_CONNECTOR, 10 * USD,
            CLPR_SUBMIT_BUNDLE, USD / 100);

    @Test
    @DisplayName("Every CLPR transaction op has its draft base fee and pays node and network fees")
    void everyClprTransactionHasItsBaseFee() throws Exception {
        final FeeSchedule schedule = load(GENESIS_SCHEDULE_PATH);

        BASE_FEES.forEach((functionality, baseFee) -> {
            final var serviceDef = lookupServiceFee(schedule, functionality);
            assertThat(serviceDef)
                    .as("genesis schedule entry for %s", functionality)
                    .isNotNull();
            assertThat(serviceDef.baseFee()).as("baseFee for %s", functionality).isEqualTo(baseFee);
            assertThat(serviceDef.nodeNetworkFeeExempt())
                    .as("nodeNetworkFeeExempt for %s — endpoints MUST pay per spec §8.10", functionality)
                    .isFalse();
        });
    }

    @Test
    @DisplayName("Bundles pay per byte; Channel completion pays for state bytes and verifier gas")
    void verifierOpsCarryTheirExtras() throws Exception {
        final FeeSchedule schedule = load(GENESIS_SCHEDULE_PATH);

        assertThat(lookupServiceFee(schedule, CLPR_SUBMIT_BUNDLE).extras())
                .containsExactly(included(Extra.CLPR_BUNDLE_BYTES, 0));
        assertThat(lookupServiceFee(schedule, CLPR_COMPLETE_CHANNEL).extras())
                .containsExactlyInAnyOrder(included(Extra.STATE_BYTES, 8_192), included(Extra.GAS, 0));
        // $0.00001 per bundle byte
        assertThat(lookupExtraFee(schedule, Extra.CLPR_BUNDLE_BYTES).fee()).isEqualTo(USD / 100_000);
    }

    @ParameterizedTest(name = "{0} upgrade schedule matches genesis")
    @ValueSource(strings = {"testnet", "previewnet"})
    @DisplayName("The testnet and previewnet upgrade schedules carry the genesis CLPR entries")
    void upgradeSchedulesMatchGenesis(final String network) throws Exception {
        final var genesis = load(GENESIS_SCHEDULE_PATH);
        final var upgrade =
                load(HEDERA_NODE.resolve(Path.of("configuration", network, "upgrade", "simpleFeesSchedules.json")));

        for (final var functionality : BASE_FEES.keySet()) {
            assertThat(lookupServiceFee(upgrade, functionality))
                    .as("%s upgrade schedule entry for %s", network, functionality)
                    .isEqualTo(lookupServiceFee(genesis, functionality));
        }
        assertThat(lookupExtraFee(upgrade, Extra.CLPR_BUNDLE_BYTES))
                .isEqualTo(lookupExtraFee(genesis, Extra.CLPR_BUNDLE_BYTES));
    }

    private static ExtraFeeReference included(final Extra extra, final int includedCount) {
        return ExtraFeeReference.newBuilder()
                .name(extra)
                .includedCount(includedCount)
                .build();
    }

    private static FeeSchedule load(final Path path) throws Exception {
        assertThat(path).as("fee schedule must exist at %s", path).exists();
        try (var in = Files.newInputStream(path)) {
            return FeeSchedule.JSON.parse(new ReadableStreamingData(in));
        }
    }
}
