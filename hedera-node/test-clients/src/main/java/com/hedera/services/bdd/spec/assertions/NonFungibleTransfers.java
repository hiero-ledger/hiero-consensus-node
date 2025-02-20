// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTokenString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Assertions;

public class NonFungibleTransfers implements ErroringAssertsProvider<List<TokenTransferList>> {
    private final Map<String, List<Triple<String, String, Long>>> changes = new HashMap<>();
    private boolean noOtherChangesTolerated = false;

    public static NonFungibleTransfers changingNFTBalances() {
        return new NonFungibleTransfers();
    }

    public NonFungibleTransfers including(String token, String sender, String receiver, long serialNumber) {
        changes.computeIfAbsent(token, ignore -> new ArrayList<>()).add(Triple.of(sender, receiver, serialNumber));
        return this;
    }

    @Override
    public ErroringAsserts<List<TokenTransferList>> assertsFor(HapiSpec spec) {
        final List<Throwable> wrongNFTChanges = new ArrayList<>();

        final Map<TokenID, String> tokenNameLookup = new HashMap<>();
        final Map<AccountID, String> accountNameLookup = new HashMap<>();
        final var registry = spec.registry();
        for (var entry : changes.entrySet()) {
            final var tokenName = entry.getKey();
            tokenNameLookup.put(registry.getTokenID(tokenName), tokenName);
            for (var change : entry.getValue()) {
                final var sender = change.getLeft();
                accountNameLookup.put(registry.getAccountID(sender), sender);
                final var receiver = change.getMiddle();
                accountNameLookup.put(asId(receiver, spec), receiver);
            }
        }

        return tokenTransfers -> {
            for (var tokenTransfer : tokenTransfers) {
                final var token = tokenTransfer.getToken();
                if (!tokenNameLookup.containsKey(token)) {
                    if (noOtherChangesTolerated) {
                        try {
                            Assertions.fail("Unexpected changes for token "
                                    + asTokenString(token)
                                    + " --> "
                                    + tokenTransfer.getTransfersList());
                        } catch (Throwable t) {
                            wrongNFTChanges.add(t);
                        }
                    }
                    continue;
                }

                final var nftChanges = tokenTransfer.getNftTransfersList();
                for (var nftChange : nftChanges) {
                    final var sender = nftChange.getSenderAccountID();
                    final var receiver = nftChange.getReceiverAccountID();
                    if (!accountNameLookup.containsKey(sender)) {
                        if (noOtherChangesTolerated) {
                            try {
                                Assertions.fail("Unexpected change to balance of account "
                                        + asAccountString(sender)
                                        + " for token "
                                        + asTokenString(token)
                                        + " with serial number --> "
                                        + nftChange.getSerialNumber());
                            } catch (Throwable t) {
                                wrongNFTChanges.add(t);
                            }
                        }
                        continue;
                    }
                    if (!accountNameLookup.containsKey(receiver)) {
                        if (noOtherChangesTolerated) {
                            try {
                                Assertions.fail("Unexpected change to balance of account "
                                        + asAccountString(receiver)
                                        + " for token "
                                        + asTokenString(token)
                                        + " with serial number --> "
                                        + nftChange.getSerialNumber());
                            } catch (Throwable t) {
                                wrongNFTChanges.add(t);
                            }
                        }
                        continue;
                    }
                    final var tokenName = tokenNameLookup.get(token);
                    final var senderName = accountNameLookup.get(sender);
                    final var receiverName = accountNameLookup.get(receiver);
                    final var actual = nftChange.getSerialNumber();
                    final var expected = expectedChange(tokenName, senderName, receiverName);
                    try {
                        Assertions.assertEquals(
                                expected,
                                actual,
                                "Wrong change in account '" + senderName + "' for token '" + tokenName + "'");
                    } catch (Throwable t) {
                        wrongNFTChanges.add(t);
                    }
                    forgetExpectation(tokenName, senderName, receiverName);
                }
            }

            for (var entry : changes.entrySet()) {
                final var token = entry.getKey();
                final var expectations = entry.getValue();
                try {
                    Assertions.assertTrue(
                            expectations.isEmpty(),
                            () -> "Expected changes for token '" + token + "', but got none of: " + expectations);
                } catch (Throwable t) {
                    wrongNFTChanges.add(t);
                }
            }
            return wrongNFTChanges;
        };
    }

    private long expectedChange(String token, String sender, String receiver) {
        final var expectations = changes.get(token);
        if (expectations == null) {
            throw new IllegalStateException("No expected NFT change from sender '"
                    + sender
                    + "' to receiver '"
                    + receiver
                    + "' for "
                    + "token '"
                    + token
                    + "'");
        }
        for (var change : expectations) {
            if (change.getLeft().equals(sender) && change.getMiddle().equals(receiver)) {
                return change.getRight();
            }
        }
        throw new IllegalStateException("No expected NFT change in account '" + sender + "' for token '" + token + "'");
    }

    private void forgetExpectation(String token, String sender, String receiver) {
        final var expectations = changes.get(token);
        if (expectations == null) {
            throw new IllegalStateException("No expected NFT change from sender '"
                    + sender
                    + "' to receiver '"
                    + receiver
                    + "' for "
                    + "token '"
                    + token
                    + "'");
        }
        for (var iter = expectations.iterator(); iter.hasNext(); ) {
            final var change = iter.next();
            if (change.getLeft().equals(sender) && change.getMiddle().equals(receiver)) {
                iter.remove();
                break;
            }
        }
    }
}
