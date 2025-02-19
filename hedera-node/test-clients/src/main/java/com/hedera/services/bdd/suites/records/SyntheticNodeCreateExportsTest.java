/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
