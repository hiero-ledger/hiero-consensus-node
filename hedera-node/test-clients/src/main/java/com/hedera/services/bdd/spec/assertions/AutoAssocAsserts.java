// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdForKeyLookUp;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenAssociation;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

public class AutoAssocAsserts {
    private static final Comparator<Pair<AccountID, TokenID>> ASSOC_CMP = Comparator.comparing(
                    Pair<AccountID, TokenID>::getLeft, Comparator.comparingLong(AccountID::getAccountNum))
            .thenComparing(Pair::getRight, Comparator.comparingLong(TokenID::getTokenNum));

    public static ErroringAssertsProvider<List<TokenAssociation>> accountTokenPairsInAnyOrder(
            final List<Pair<String, String>> expectedAssociations) {
        return internalAccountTokenPairs(expectedAssociations, true);
    }

    public static ErroringAssertsProvider<List<TokenAssociation>> accountTokenPairs(
            final List<Pair<String, String>> expectedAssociations) {
        return internalAccountTokenPairs(expectedAssociations, false);
    }

    private static ErroringAssertsProvider<List<TokenAssociation>> internalAccountTokenPairs(
            final List<Pair<String, String>> expectedAssociations, final boolean sortBeforeCmp) {
        return spec -> actual -> {
            try {
                final var actualNum = actual.size();
                final var expectedNum = expectedAssociations.size();
                assertEquals(
                        expectedNum,
                        actualNum,
                        "Expected " + expectedNum + " auto-associations, got " + actualNum + " (" + actual + ")");
                final List<Pair<AccountID, TokenID>> actualPairs = new ArrayList<>();
                final List<Pair<AccountID, TokenID>> expectedPairs = new ArrayList<>();

                int nextActual = 0;
                final var registry = spec.registry();
                for (final var expectedAssoc : expectedAssociations) {
                    final var actualAssoc = actual.get(nextActual++);
                    final var actualPair = Pair.of(actualAssoc.getAccountId(), actualAssoc.getTokenId());
                    actualPairs.add(actualPair);

                    final var expectedPair = Pair.of(
                            asIdForKeyLookUp(expectedAssoc.getLeft(), spec),
                            registry.getTokenID(expectedAssoc.getRight()));
                    expectedPairs.add(expectedPair);
                }

                if (sortBeforeCmp) {
                    actualPairs.sort(ASSOC_CMP);
                    expectedPairs.sort(ASSOC_CMP);
                }

                assertEquals(expectedPairs, actualPairs, "Wrong auto-associations");

                return Collections.emptyList();
            } catch (Exception ex) {
                return List.of(ex);
            }
        };
    }
}
