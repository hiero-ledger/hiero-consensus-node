// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.services.bdd.spec.assertions.EqualityAssertsProviderFactory.shouldBe;
import static com.hedera.services.bdd.spec.assertions.EqualityAssertsProviderFactory.shouldNotBe;
import static java.util.Collections.EMPTY_LIST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.QueryUtils;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.AssessedCustomFee;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.PendingAirdropId;
import com.hederahashgraph.api.proto.java.PendingAirdropRecord;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenAssociation;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.opentest4j.AssertionFailedError;

public class TransactionRecordAsserts extends BaseErroringAssertsProvider<TransactionRecord> {

    static final Logger log = LogManager.getLogger(TransactionRecordAsserts.class);
    static final String RECEIPT = "receipt";
    static final String TRANSACTION_FEE = "transactionFee";

    public static TransactionRecordAsserts recordWith() {
        return new TransactionRecordAsserts();
    }

    public static ErroringAssertsProvider<List<TokenTransferList>> includingFungibleMovement(
            final TokenMovement movement) {
        return includingMovement(movement, true);
    }

    public static ErroringAssertsProvider<List<TokenTransferList>> includingNonfungibleMovement(
            final TokenMovement movement) {
        return includingMovement(movement, false);
    }

    private static ErroringAssertsProvider<List<TokenTransferList>> includingMovement(
            final TokenMovement movement, final boolean fungible) {
        return spec -> {
            final var tokenXfer = fungible ? movement.specializedFor(spec) : movement.specializedForNft(spec);
            final var tokenId = tokenXfer.getToken();
            return (ErroringAsserts<List<TokenTransferList>>) allXfers -> {
                List<Throwable> errs = Collections.emptyList();
                var found = false;
                try {
                    for (final var scopedXfers : allXfers) {
                        if (tokenId.equals(scopedXfers.getToken())) {
                            found = true;
                            if (tokenXfer.getNftTransfersCount() > 0) {
                                for (final var xfer : tokenXfer.getNftTransfersList()) {
                                    if (!scopedXfers.getNftTransfersList().contains(xfer)) {
                                        found = false;
                                        break;
                                    }
                                }
                            } else {
                                for (final var xfer : tokenXfer.getTransfersList()) {
                                    if (!scopedXfers.getTransfersList().contains(xfer)) {
                                        found = false;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (!found) {
                        Assertions.fail("Expected token transfers " + tokenXfer + " but not present in " + allXfers);
                    }
                } catch (Throwable t) {
                    errs = List.of(t);
                }
                return errs;
            };
        };
    }

    public TransactionRecordAsserts payer(String account) {
        registerIdLookupAssert(account, r -> r.getTransactionID().getAccountID(), AccountID.class, "Bad payer!");
        return this;
    }

    public static ErroringAssertsProvider<List<PendingAirdropRecord>> includingFungiblePendingAirdrop(
            final TokenMovement... movements) {
        var listOfMovements = List.of(movements);
        return includingPendingAirdrop(listOfMovements, true);
    }

    public static ErroringAssertsProvider<List<PendingAirdropRecord>> includingNftPendingAirdrop(
            final TokenMovement... movements) {
        var listOfMovements = List.of(movements);
        return includingPendingAirdrop(listOfMovements, false);
    }

    private static ErroringAssertsProvider<List<PendingAirdropRecord>> includingPendingAirdrop(
            final List<TokenMovement> movements, final boolean fungible) {

        return spec -> {
            // convert movements to pending airdrop records
            List<PendingAirdropRecord> expectedRecords = new ArrayList<>();
            movements.forEach(tokenMovement -> {
                if (fungible) {
                    var pendingAirdropRecords = tokenMovement.specializedForPendingAirdrop(spec);
                    if (!pendingAirdropRecords.isEmpty()) {
                        expectedRecords.addAll(pendingAirdropRecords);
                    }
                } else {
                    var pendingAirdropRecords = tokenMovement.specializedForNftPendingAirdop(spec);
                    if (!pendingAirdropRecords.isEmpty()) {
                        expectedRecords.addAll(pendingAirdropRecords);
                    }
                }
            });
            return (ErroringAsserts<List<PendingAirdropRecord>>) allPendingAirdrops -> {
                List<Throwable> errs = Collections.emptyList();
                AtomicBoolean found = new AtomicBoolean(true);
                try {
                    expectedRecords.stream().takeWhile(n -> found.get()).forEach(record -> {
                        if (!allPendingAirdrops.contains(record)) {
                            found.set(false);
                        }
                    });
                    if (!found.get()) {
                        Assertions.fail("Expected pending airdrops " + expectedRecords + " but not present in "
                                + allPendingAirdrops);
                    }
                } catch (Throwable t) {
                    errs = List.of(t);
                }
                return errs;
            };
        };
    }

    public TransactionRecordAsserts pendingAirdrops(ErroringAssertsProvider<List<PendingAirdropRecord>> provider) {
        registerTypedProvider("newPendingAirdropsList", provider);
        return this;
    }

    public TransactionRecordAsserts pendingAirdropsCount(final int n) {
        this.<List<PendingAirdropId>>registerTypedProvider("newPendingAirdropsList", spec -> pendingAirdrops -> {
            try {
                assertEquals(n, pendingAirdrops.size(), "Wrong # of pending airdrops");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts txnId(String expectedTxn) {
        this.<TransactionID>registerTypedProvider("transactionID", spec -> txnId -> {
            try {
                assertEquals(spec.registry().getTxnId(expectedTxn), txnId, "Wrong txnId!");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts consensusTimeImpliedByOffset(final Timestamp parentTime, final int nonce) {
        this.<Timestamp>registerTypedProvider("consensusTimestamp", spec -> actualTime -> {
            try {
                final var expectedTime = parentTime.toBuilder()
                        .setNanos(parentTime.getNanos() + nonce)
                        .build();
                assertEquals(expectedTime, actualTime);
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts parentConsensusTime(final Timestamp parentTime) {
        this.<Timestamp>registerTypedProvider("parentConsensusTimestamp", spec -> actualTime -> {
            try {
                assertEquals(parentTime, actualTime);
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts txnId(TransactionID expectedTxn) {
        this.<TransactionID>registerTypedProvider("transactionID", spec -> txnId -> {
            try {
                assertEquals(expectedTxn, txnId, "Wrong txnId!");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts pseudoRandomBytes() {
        this.<ByteString>registerTypedProvider("prngBytes", spec -> prngBytes -> {
            try {
                Assertions.assertNotNull(prngBytes, "Null prngBytes!");
                assertEquals(32, prngBytes.size(), "Wrong prngBytes!");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts alias(ByteString alias) {
        registerTypedProvider("alias", shouldBe(alias));
        return this;
    }

    @SuppressWarnings("java:S1181")
    public TransactionRecordAsserts assessedCustomFeeCount(final int n) {
        this.<List<AssessedCustomFee>>registerTypedProvider("assessedCustomFeesList", spec -> assessedCustomFees -> {
            try {
                assertEquals(n, assessedCustomFees.size(), "Wrong # of custom fees: " + assessedCustomFees);
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts statusFrom(@NonNull final ResponseCodeEnum... expectedStatuses) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            final var actual = receipt.getStatus();
            try {
                for (final var expected : expectedStatuses) {
                    if (actual == expected) {
                        return Collections.emptyList();
                    }
                }
                throw new AssertionFailedError(
                        "Expected status in " + List.of(expectedStatuses) + " but was " + actual);
            } catch (Throwable t) {
                return List.of(t);
            }
        });
        return this;
    }

    public TransactionRecordAsserts status(ResponseCodeEnum expectedStatus) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                assertEquals(expectedStatus, receipt.getStatus(), "Bad status!");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts serialNos(List<Long> minted) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                assertEquals(minted, receipt.getSerialNumbersList(), "Wrong serial nos");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts newTotalSupply(long expected) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                assertEquals(expected, receipt.getNewTotalSupply(), "Wrong new total supply");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts targetedContractId(final String id) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                final var expected = TxnUtils.asContractId(id, spec);
                assertEquals(expected, receipt.getContractID(), "Bad targeted contract");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts hasMirrorIdInReceipt() {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                assertEquals(
                        spec.setup().defaultShard().getShardNum(),
                        receipt.getContractID().getShardNum(),
                        "Bad receipt shard");
                assertEquals(
                        spec.setup().defaultRealm().getRealmNum(),
                        receipt.getContractID().getRealmNum(),
                        "Bad receipt realm");
            } catch (Exception t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts targetedContractId(final ContractID id) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                assertEquals(id, receipt.getContractID(), "Bad targeted contract");
            } catch (Exception t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts checkTopicRunningHashVersion(int versionNumber) {
        this.<TransactionReceipt>registerTypedProvider(RECEIPT, spec -> receipt -> {
            try {
                assertEquals(versionNumber, receipt.getTopicRunningHashVersion(), "Bad TopicRunningHashVerions!");
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts contractCallResult(ContractFnResultAsserts provider) {
        registerTypedProvider("contractCallResult", provider);
        return this;
    }

    public TransactionRecordAsserts contractCreateResult(ContractFnResultAsserts provider) {
        registerTypedProvider("contractCreateResult", provider);
        return this;
    }

    public TransactionRecordAsserts transfers(TransferListAsserts provider) {
        registerTypedProvider("transferList", provider);
        return this;
    }

    public TransactionRecordAsserts tokenTransfers(BaseErroringAssertsProvider<List<TokenTransferList>> provider) {
        registerTypedProvider("tokenTransferListsList", provider);
        return this;
    }

    public TransactionRecordAsserts fee(Long amount) {
        registerTypedProvider(TRANSACTION_FEE, shouldBe(amount));
        return this;
    }

    public TransactionRecordAsserts feeGreaterThan(final long amount) {
        this.<Long>registerTypedProvider(TRANSACTION_FEE, spec -> fee -> {
            try {
                assertTrue(fee > amount, "Fee should have exceeded " + amount + " but was " + fee);
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts feeDifferentThan(Long amount) {
        registerTypedProvider(TRANSACTION_FEE, shouldNotBe(amount));
        return this;
    }

    public TransactionRecordAsserts memo(String text) {
        registerTypedProvider("memo", shouldBe(text));
        return this;
    }

    public TransactionRecordAsserts hasNoAlias() {
        registerTypedProvider("alias", shouldBe(ByteString.EMPTY));
        return this;
    }

    public TransactionRecordAsserts evmAddress(ByteString evmAddress) {
        registerTypedProvider("evmAddress", shouldBe(evmAddress));
        return this;
    }

    public TransactionRecordAsserts fee(Function<HapiSpec, Long> amountFn) {
        registerTypedProvider(TRANSACTION_FEE, shouldBe(amountFn));
        return this;
    }

    public TransactionRecordAsserts tokenTransfers(ErroringAssertsProvider<List<TokenTransferList>> provider) {
        registerTypedProvider("tokenTransferListsList", provider);
        return this;
    }

    public TransactionRecordAsserts autoAssociated(ErroringAssertsProvider<List<TokenAssociation>> provider) {
        registerTypedProvider("automaticTokenAssociationsList", provider);
        return this;
    }

    public TransactionRecordAsserts autoAssociationCount(int autoAssociations) {
        this.<List<TokenAssociation>>registerTypedProvider("automaticTokenAssociationsList", spec -> associations -> {
            try {
                assertEquals(
                        autoAssociations, associations.size(), "Wrong # of automatic associations: " + associations);
            } catch (Throwable t) {
                return List.of(t);
            }
            return EMPTY_LIST;
        });
        return this;
    }

    public TransactionRecordAsserts ethereumHash(ByteString hash) {
        registerTypedProvider("ethereumHash", shouldBe(hash));
        return this;
    }

    private <T> void registerTypedProvider(String forField, ErroringAssertsProvider<T> provider) {
        try {
            Method m = TransactionRecord.class.getMethod(QueryUtils.asGetter(forField));
            registerProvider((spec, o) -> {
                TransactionRecord transactionRecord = (TransactionRecord) o;
                T instance = (T) m.invoke(transactionRecord);
                ErroringAsserts<T> asserts = provider.assertsFor(spec);
                List<Throwable> errors = asserts.errorsIn(instance);
                AssertUtils.rethrowSummaryError(log, "Bad " + forField + "!", errors);
            });
        } catch (Exception e) {
            log.warn(
                    String.format("Unable to register asserts provider for TransactionRecord field '%s'", forField), e);
        }
    }
}
