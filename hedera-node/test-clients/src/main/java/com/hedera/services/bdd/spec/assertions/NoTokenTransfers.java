// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;

public class NoTokenTransfers implements ErroringAssertsProvider<List<TokenTransferList>> {
    public static NoTokenTransfers emptyTokenTransfers() {
        return new NoTokenTransfers();
    }

    @Override
    public ErroringAsserts<List<TokenTransferList>> assertsFor(HapiSpec spec) {
        return tokenTransfers -> {
            try {
                Assertions.assertTrue(
                        tokenTransfers.isEmpty(), () -> "Expected no token transfers, were: " + tokenTransfers);
            } catch (Throwable t) {
                return List.of(t);
            }
            return Collections.emptyList();
        };
    }
}
