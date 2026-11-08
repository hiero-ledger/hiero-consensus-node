// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_ENDPOINT_PUBLICATION;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_EVICT_PEER_ENDPOINT;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_GET_ENDPOINT_MANIFEST;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_GET_LEDGER_CONFIGURATION;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the CLPR fee proposal's prices in the genesis {@code simpleFeesSchedules.json} and the testnet and previewnet
 * upgrade schedules, so that any drift in a base fee, extra, or node/network exemption is caught.
 */
class ClprGenesisFeeScheduleTest {

    /** Tinycents per USD (10^8 tinycents per cent). */
    private static final long USD = 10_000_000_000L;

    private static final long ONE_CENT = USD / 100;

    /** Relative paths from the {@code hedera-clpr-service-impl} module's working directory (where Gradle runs tests). */
    private static final List<Path> SCHEDULE_PATHS = List.of(
            Path.of(
                    "..",
                    "hedera-file-service-impl",
                    "src",
                    "main",
                    "resources",
                    "genesis",
                    "simpleFeesSchedules.json"),
            Path.of("..", "configuration", "testnet", "upgrade", "simpleFeesSchedules.json"),
            Path.of("..", "configuration", "previewnet", "upgrade", "simpleFeesSchedules.json"));

    private record Price(long baseFee, Map<Extra, Integer> includedExtras, boolean nodeNetworkExempt) {
        static Price of(final long baseFee) {
            return new Price(baseFee, Map.of(), false);
        }

        Price withExtras(final Map<Extra, Integer> includedExtras) {
            return new Price(baseFee, includedExtras, nodeNetworkExempt);
        }

        Price exemptFromNodeAndNetworkFees() {
            return new Price(baseFee, includedExtras, true);
        }
    }

    /** The proposal's price for every CLPR transaction and query. */
    private static final Map<HederaFunctionality, Price> EXPECTED = Map.ofEntries(
            Map.entry(CLPR_REGISTER_CHANNEL, Price.of(100 * USD)),
            Map.entry(
                    CLPR_COMPLETE_CHANNEL,
                    Price.of(100 * USD).withExtras(Map.of(Extra.STATE_BYTES, 8_192, Extra.GAS, 0))),
            Map.entry(CLPR_REGISTER_CONNECTOR, Price.of(10 * USD)),
            Map.entry(CLPR_COMPLETE_CONNECTOR, Price.of(25 * USD)),
            Map.entry(CLPR_DEREGISTER_CONNECTOR, Price.of(ONE_CENT)),
            // The proposal prices a bundle at $0.01 + bundle bytes + verifier gas in total, so its bytes are not
            // charged again by the node and network fees
            Map.entry(
                    CLPR_SUBMIT_BUNDLE,
                    Price.of(ONE_CENT)
                            .withExtras(Map.of(Extra.PROCESSING_BYTES, 0, Extra.GAS, 0))
                            .exemptFromNodeAndNetworkFees()),
            // Admin-only: a privileged payer's fees are waived
            Map.entry(CLPR_CLOSE_CHANNEL, Price.of(0)),
            Map.entry(CLPR_EVICT_PEER_ENDPOINT, Price.of(0)),
            Map.entry(CLPR_UPDATE_LEDGER_CONFIGURATION, Price.of(0)),
            // Node-submitted; left free until nodes are funded to pay for it
            Map.entry(CLPR_ENDPOINT_PUBLICATION, new Price(0, Map.of(), true)),
            Map.entry(CLPR_GET_LEDGER_CONFIGURATION, Price.of(ONE_CENT).withExtras(Map.of(Extra.GAS, 0))),
            Map.entry(CLPR_GET_ENDPOINT_MANIFEST, Price.of(ONE_CENT).withExtras(Map.of(Extra.GAS, 0))));

    @ParameterizedTest(name = "{0} prices {1} per the CLPR fee proposal")
    @MethodSource("schedulesAndFunctions")
    void scheduleMatchesProposal(final Path schedulePath, final HederaFunctionality functionality) throws Exception {
        final var schedule = load(schedulePath);
        final var expected = EXPECTED.get(functionality);

        final var serviceDef = lookupServiceFee(schedule, functionality);

        assertThat(serviceDef)
                .as("%s entry for %s", schedulePath, functionality)
                .isNotNull();
        assertThat(serviceDef.baseFee()).as("baseFee for %s", functionality).isEqualTo(expected.baseFee());
        assertThat(serviceDef.extras().stream()
                        .collect(Collectors.toMap(ExtraFeeReference::name, ExtraFeeReference::includedCount)))
                .as("extras for %s", functionality)
                .isEqualTo(expected.includedExtras());
        assertThat(serviceDef.nodeNetworkFeeExempt())
                .as("nodeNetworkFeeExempt for %s", functionality)
                .isEqualTo(expected.nodeNetworkExempt());
        assertThat(serviceDef.free()).as("free for %s", functionality).isFalse();
    }

    private static Stream<Arguments> schedulesAndFunctions() {
        return SCHEDULE_PATHS.stream()
                .flatMap(path -> EXPECTED.keySet().stream().map(functionality -> Arguments.of(path, functionality)));
    }

    private static FeeSchedule load(final Path path) throws Exception {
        assertThat(path).as("fee schedule must exist at the expected path").exists();
        try (var in = Files.newInputStream(path)) {
            return FeeSchedule.JSON.parse(new ReadableStreamingData(in));
        }
    }
}
