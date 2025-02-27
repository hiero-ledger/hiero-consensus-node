// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.statusHistograms;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.visibleItems;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Asserts the synthetic node creations after the network has handled the genesis transaction.
 */
public class SyntheticNodeCreateExportsTest {
    @GenesisHapiTest
    final Stream<DynamicTest> syntheticNodeCreatesExternalizedAtGenesis() {
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(visibleItems(syntheticNodeCreatesValidator(), "genesisTxn")),
                // This is the genesis transaction
                cryptoCreate("firstUser").via("genesisTxn"));
    }

    private static VisibleItemsValidator syntheticNodeCreatesValidator() {
        return (spec, records) -> {
            final var items = requireNonNull(records.get("genesisTxn"));
            final var histogram = statusHistograms(items.entries());
            assertEquals(Map.of(SUCCESS, CLASSIC_HAPI_TEST_NETWORK_SIZE), histogram.get(NodeCreate));
        };
    }
}
