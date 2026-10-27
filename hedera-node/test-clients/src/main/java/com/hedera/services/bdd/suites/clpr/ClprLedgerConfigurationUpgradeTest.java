// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.simulatePostUpgradeTransaction;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.writeToNodeWorkingDirs;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprUpdateLedgerConfiguration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a changed CLPR ledger configuration file in the post-upgrade system files directory is
 * applied at the upgrade boundary with a synthetic {@code ClprUpdateLedgerConfiguration}, visible in the
 * record stream, the same way {@code throttles.json} is applied with a synthetic {@code FileUpdate}.
 */
@Tag(CLPR)
// Genesis tests build their own network, so they must run before any shared network exists
@Order(Integer.MIN_VALUE)
public class ClprLedgerConfigurationUpgradeTest {
    private static final ClprThrottles UPGRADE_THROTTLES = ClprThrottles.newBuilder()
            .setMaxMessagesPerBundle(500)
            .setMaxMessagePayloadBytes(65_536)
            .setMaxGasPerMessage(15_000_000L)
            .setMaxQueueDepth(20_000)
            .setMaxSyncBytes(4_194_304L)
            .build();

    private static final String UPGRADE_FILE_JSON = """
            {
              "throttles": {
                "maxMessagesPerBundle": 500,
                "maxMessagePayloadBytes": 65536,
                "maxGasPerMessage": 15000000,
                "maxQueueDepth": 20000,
                "maxSyncBytes": 4194304
              }
            }
            """;

    @GenesisHapiTest
    final Stream<DynamicTest> changedLedgerConfigurationFileIsAppliedAtUpgradeBoundary() {
        return hapiTest(
                streamMustIncludePassFrom(selectedItems(
                        clprLedgerConfigurationUpdateValidator(),
                        1,
                        ClprLedgerConfigurationUpgradeTest::isClprLedgerConfigurationUpdate)),
                // This is the genesis transaction, which seeds the ledger configuration
                cryptoCreate("genesisAccount"),
                // Genesis seeds the bundled throttles and derives the chain id from contracts.chainId
                doWithStartupConfig("contracts.chainId", chainId -> clprGetLedgerConfiguration()
                        .chainId("eip155:" + chainId)
                        .maxMessagesPerBundle(1_000)
                        .maxQueueDepth(10_000)),
                // Now write the upgrade file to the node's working dirs
                doWithStartupConfig(
                        "clpr.upgradeLedgerConfigurationFile",
                        file -> writeToNodeWorkingDirs(UPGRADE_FILE_JSON, "data", "config", file)),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // The post-upgrade setup runs when the next transaction is handled
                cryptoCreate("postUpgradeAccount"),
                // Then verify the new throttles are in effect
                clprGetLedgerConfiguration().maxMessagesPerBundle(500).maxQueueDepth(20_000),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    private static boolean isClprLedgerConfigurationUpdate(
            @NonNull final HapiSpec spec, @NonNull final RecordStreamItem item) {
        final var sysAdminNum = spec.startupProperties().getLong("accounts.systemAdmin");
        if (item.getRecord().getTransactionID().getAccountID().getAccountNum() != sysAdminNum) {
            return false;
        }
        return RecordStreamEntry.from(item).function() == ClprUpdateLedgerConfiguration;
    }

    private static VisibleItemsValidator clprLedgerConfigurationUpdateValidator() {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No post-upgrade ClprUpdateLedgerConfiguration found");
            final var updates = items.entries().stream()
                    .filter(item -> item.function() == ClprUpdateLedgerConfiguration)
                    .toList();
            assertEquals(1, updates.size(), "Expected exactly one ClprUpdateLedgerConfiguration");
            final var configuration =
                    updates.getFirst().body().getClprUpdateLedgerConfiguration().getConfiguration();
            assertEquals(
                    ClprLedgerConfiguration.newBuilder()
                            .setThrottles(UPGRADE_THROTTLES)
                            .build(),
                    configuration);
        };
    }
}
