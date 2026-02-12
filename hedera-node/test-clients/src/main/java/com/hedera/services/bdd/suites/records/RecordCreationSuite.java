// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.including;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingDeduction;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.fee.FeeObject;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class RecordCreationSuite {
    private static final String FOR_ACCOUNT_FUNDING = "98";
    private static final String FOR_ACCOUNT_STAKING_REWARDS = "800";
    private static final String FOR_ACCOUNT_NODE_REWARD = "801";
    private static final String PAYER = "payer";
    private static final String THIS_IS_OK_IT_S_FINE_IT_S_WHATEVER = "This is ok, it's fine, it's whatever.";
    private static final String TO_ACCOUNT = "3";
    private static final String TXN_ID = "txnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.nodeRewardsEnabled", "false",
                "nodes.preserveMinNodeRewardBalance", "false",
                "nodes.feeCollectionAccountEnabled", "false"));
    }

    @LeakyHapiTest(
            requirement = SYSTEM_ACCOUNT_BALANCES,
            overrides = {"nodes.feeCollectionAccountEnabled"})
    final Stream<DynamicTest> submittingNodeStillPaidIfServiceFeesOmitted() {
        final String comfortingMemo = THIS_IS_OK_IT_S_FINE_IT_S_WHATEVER;
        final AtomicReference<FeeObject> feeObs = new AtomicReference<>();

        return hapiTest(
                overriding("nodes.feeCollectionAccountEnabled", "false"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, TO_ACCOUNT, ONE_HBAR)).payingWith(GENESIS),
                cryptoCreate(PAYER),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                        .memo(comfortingMemo)
                        .exposingFeesTo(feeObs)
                        .payingWith(PAYER),
                sourcing(() -> cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                        .memo(comfortingMemo)
                        .fee(feeObs.get().networkFee() + feeObs.get().nodeFee())
                        .payingWith(PAYER)
                        .via(TXN_ID)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .logged()),
                sourcing(() -> getTxnRecord(TXN_ID)
                        .assertingNothingAboutHashes()
                        .hasPriority(recordWith()
                                .transfers(includingDeduction(
                                        PAYER,
                                        feeObs.get().networkFee() + feeObs.get().nodeFee()))
                                .transfers(including(spec -> {
                                    final var networkFee = feeObs.get().networkFee();
                                    final var nodeFee = feeObs.get().nodeFee();
                                    return TransferList.newBuilder()
                                            .addAllAccountAmounts(List.of(
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(PAYER, spec))
                                                            .setAmount(-(networkFee + nodeFee))
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(TO_ACCOUNT, spec))
                                                            .setAmount(+nodeFee)
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(FOR_ACCOUNT_FUNDING, spec))
                                                            .setAmount((long) (networkFee * 0.8 + 1))
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(FOR_ACCOUNT_STAKING_REWARDS, spec))
                                                            .setAmount((long) (networkFee * 0.1))
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(FOR_ACCOUNT_NODE_REWARD, spec))
                                                            .setAmount((long) (networkFee * 0.1))
                                                            .build()))
                                            .build();
                                }))
                                .status(INSUFFICIENT_TX_FEE))
                        .logged()));
    }

    @LeakyRepeatableHapiTest(
            value = NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            overrides = {"nodes.feeCollectionAccountEnabled"})
    @Tag(MATS)
    final Stream<DynamicTest> submittingNodeChargedNetworkFeeForLackOfDueDiligence() {
        final String disquietingMemo = "\u0000his is ok, it's fine, it's whatever.";

        return hapiTest(
                overriding("nodes.feeCollectionAccountEnabled", "false"),
                cryptoCreate(PAYER),
                cryptoTransfer(tinyBarsFromTo(GENESIS, TO_ACCOUNT, ONE_HBAR)).payingWith(GENESIS),
                usableTxnIdNamed(TXN_ID).payerId(PAYER),
                uncheckedSubmit(cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                                .memo(disquietingMemo)
                                .payingWith(PAYER)
                                .txnId(TXN_ID))
                        .payingWith(GENESIS),
                withOpContext((spec, opLog) -> {
                    final var lookup = getTxnRecord(TXN_ID).assertingNothingAboutHashes();
                    allRunFor(spec, lookup);

                    final var record = lookup.getResponseRecord();
                    assertEquals(
                            INVALID_ZERO_BYTE_IN_STRING, record.getReceipt().getStatus());

                    final var transfers = record.getTransferList().getAccountAmountsList();
                    final var nodeId = asId(TO_ACCOUNT, spec);
                    final long nodeNet = transfers.stream()
                            .filter(aa -> aa.getAccountID().equals(nodeId))
                            .mapToLong(AccountAmount::getAmount)
                            .sum();
                    assertTrue(nodeNet < 0, "Expected a net deduction from 0.0." + TO_ACCOUNT + " but was " + nodeNet);

                    final long chargedFee = -nodeNet;
                    final long expectedStakingRewards = chargedFee / 10;
                    final long expectedNodeRewards = chargedFee / 10;
                    final long expectedFunding = chargedFee - expectedStakingRewards - expectedNodeRewards;

                    final long actualFunding = transfers.stream()
                            .filter(aa -> aa.getAccountID().equals(asId(FOR_ACCOUNT_FUNDING, spec)))
                            .mapToLong(AccountAmount::getAmount)
                            .sum();
                    final long actualStakingRewards = transfers.stream()
                            .filter(aa -> aa.getAccountID().equals(asId(FOR_ACCOUNT_STAKING_REWARDS, spec)))
                            .mapToLong(AccountAmount::getAmount)
                            .sum();
                    final long actualNodeRewards = transfers.stream()
                            .filter(aa -> aa.getAccountID().equals(asId(FOR_ACCOUNT_NODE_REWARD, spec)))
                            .mapToLong(AccountAmount::getAmount)
                            .sum();

                    assertEquals(expectedFunding, actualFunding, "Bad funding split");
                    assertEquals(expectedStakingRewards, actualStakingRewards, "Bad staking rewards split");
                    assertEquals(expectedNodeRewards, actualNodeRewards, "Bad node rewards split");
                }));
    }

    @LeakyHapiTest(
            requirement = SYSTEM_ACCOUNT_BALANCES,
            overrides = {"nodes.feeCollectionAccountEnabled"})
    final Stream<DynamicTest> submittingNodeChargedNetworkFeeForIgnoringPayerUnwillingness() {
        final String comfortingMemo = THIS_IS_OK_IT_S_FINE_IT_S_WHATEVER;
        final AtomicReference<FeeObject> feeObs = new AtomicReference<>();

        return hapiTest(
                overriding("nodes.feeCollectionAccountEnabled", "false"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, TO_ACCOUNT, ONE_HBAR)).payingWith(GENESIS),
                cryptoCreate(PAYER),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                        .memo(comfortingMemo)
                        .exposingFeesTo(feeObs)
                        .payingWith(PAYER),
                usableTxnIdNamed(TXN_ID).payerId(PAYER),
                sourcing(() -> uncheckedSubmit(cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                                .memo(comfortingMemo)
                                .fee(feeObs.get().networkFee() - 1L)
                                .payingWith(PAYER)
                                .txnId(TXN_ID))
                        .payingWith(GENESIS)),
                sourcing(() -> getTxnRecord(TXN_ID)
                        .assertingNothingAboutHashes()
                        .hasPriority(recordWith()
                                .transfers(includingDeduction(
                                        () -> 3L, feeObs.get().networkFee()))
                                .transfers(including(spec -> {
                                    final var networkFee = feeObs.get().networkFee();
                                    return TransferList.newBuilder()
                                            .addAllAccountAmounts(List.of(
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(TO_ACCOUNT, spec))
                                                            .setAmount(-networkFee)
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(FOR_ACCOUNT_FUNDING, spec))
                                                            .setAmount((long) (networkFee * 0.8 + 1))
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(FOR_ACCOUNT_STAKING_REWARDS, spec))
                                                            .setAmount((long) (networkFee * 0.1))
                                                            .build(),
                                                    AccountAmount.newBuilder()
                                                            .setAccountID(asId(FOR_ACCOUNT_NODE_REWARD, spec))
                                                            .setAmount((long) (networkFee * 0.1))
                                                            .build()))
                                            .build();
                                }))
                                .status(INSUFFICIENT_TX_FEE))
                        .logged()));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> accountsGetPayerRecordsIfSoConfigured() {
        final var txn = "ofRecord";

        return hapiTest(
                cryptoCreate(PAYER),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1_000L))
                        .payingWith(PAYER)
                        .via(txn),
                getAccountRecords(PAYER).has(inOrder(recordWith().txnId(txn))));
    }
}
