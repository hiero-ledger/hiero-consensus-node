// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;

public class NoNftTransfers implements ErroringAssertsProvider<List<TokenTransferList>> {
    public static NoNftTransfers changingNoNftOwners() {
        return new NoNftTransfers();
    }

    @Override
    public ErroringAsserts<List<TokenTransferList>> assertsFor(HapiSpec spec) {
        final List<Throwable> unexpectedOwnershipChanges = new ArrayList<>();
        return tokenTransfers -> {
            for (var tokenTransfer : tokenTransfers) {
                try {
                    final var ownershipChanges = tokenTransfer.getNftTransfersList();
                    Assertions.assertTrue(
                            ownershipChanges.isEmpty(), () -> "Expected no NFT transfers, were: " + ownershipChanges);
                } catch (Throwable t) {
                    unexpectedOwnershipChanges.add(t);
                }
            }
            return unexpectedOwnershipChanges;
        };
    }
}
