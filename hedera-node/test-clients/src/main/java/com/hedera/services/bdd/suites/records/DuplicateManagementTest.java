// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MANIPULATES_EVENT_VERSION;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.embedded.SyntheticVersion.PAST;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.reducedFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingDeduction;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.getNonFeeDeduction;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usingVersion;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.hedera.embedded.SyntheticVersion;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class DuplicateManagementTest {
    private static final String REPEATED = "repeated";
    public static final String TXN_ID = "txnId";
    private static final String TO = asEntityString(3);
    private static final String CIVILIAN = "civilian";
    private static final long MS_TO_WAIT_FOR_CONSENSUS = 6_000L;

    @HapiTest
    @SuppressWarnings("java:S5960")
    final Stream<DynamicTest> hasExpectedDuplicates() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                usableTxnIdNamed(TXN_ID).payerId(CIVILIAN),
                uncheckedSubmit(cryptoCreate(REPEATED).payingWith(CIVILIAN).txnId(TXN_ID))
                        .payingWith(CIVILIAN)
                        .fee(ONE_HBAR)
                        .hasPrecheckFrom(NOT_SUPPORTED, BUSY),
                uncheckedSubmit(cryptoCreate(REPEATED).payingWith(CIVILIAN).txnId(TXN_ID)),
                uncheckedSubmit(cryptoCreate(REPEATED).payingWith(CIVILIAN).txnId(TXN_ID)),
                uncheckedSubmit(cryptoCreate(REPEATED).payingWith(CIVILIAN).txnId(TXN_ID)),
                sleepFor(MS_TO_WAIT_FOR_CONSENSUS),
                getReceipt(TXN_ID)
                        .andAnyDuplicates()
                        .payingWith(CIVILIAN)
                        .hasPriorityStatus(SUCCESS)
                        .hasDuplicateStatuses(DUPLICATE_TRANSACTION, DUPLICATE_TRANSACTION),
                getTxnRecord(TXN_ID)
                        .payingWith(CIVILIAN)
                        .via("cheapTxn")
                        .assertingNothingAboutHashes()
                        .hasPriority(recordWith().status(SUCCESS)),
                getTxnRecord(TXN_ID)
                        .andAnyDuplicates()
                        .payingWith(CIVILIAN)
                        .via("costlyTxn")
                        .assertingNothingAboutHashes()
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasDuplicates(inOrder(
                                recordWith().status(DUPLICATE_TRANSACTION),
                                recordWith().status(DUPLICATE_TRANSACTION))),
                sleepFor(MS_TO_WAIT_FOR_CONSENSUS),
                withOpContext((spec, opLog) -> {
                    var cheapGet = getTxnRecord("cheapTxn").assertingNothingAboutHashes();
                    var costlyGet = getTxnRecord("costlyTxn").assertingNothingAboutHashes();
                    allRunFor(spec, cheapGet, costlyGet);
                    var cheapRecord = cheapGet.getResponseRecord();
                    var costlyRecord = costlyGet.getResponseRecord();
                    opLog.info("cheapRecord: {}", cheapRecord);
                    opLog.info("costlyRecord: {}", costlyRecord);
                    var cheapPrice = getNonFeeDeduction(cheapRecord).orElse(0);
                    var costlyPrice = getNonFeeDeduction(costlyRecord).orElse(0);
                    assertEquals(
                            3 * cheapPrice - 1,
                            costlyPrice,
                            String.format(
                                    "Costly (%d) should be 3x more expensive than" + " cheap (%d)!",
                                    costlyPrice, cheapPrice));
                }));
    }

    @EmbeddedHapiTest(MANIPULATES_EVENT_VERSION)
    @DisplayName("only warns of missing creator if event version is current")
    final Stream<DynamicTest> onlyWarnsOfMissingCreatorIfCurrentVersion() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, ONE_HBAR))
                        .setNode(asEntityString(666))
                        .withSubmissionStrategy(usingVersion(PAST))
                        .hasAnyStatusAtAll(),
                assertHgcaaLogDoesNotContain(
                        byNodeId(0), "node 666 which is not in the address book", Duration.ofSeconds(1)),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, ONE_HBAR))
                        .setNode(asEntityString(666))
                        .withSubmissionStrategy(usingVersion(SyntheticVersion.PRESENT))
                        .hasAnyStatusAtAll(),
                assertHgcaaLogContains(
                        byNodeId(0), "node 666 which is not in the address book", Duration.ofSeconds(1)));
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST, requirement = SYSTEM_ACCOUNT_BALANCES)
    @DisplayName("if a node submits an authorized transaction without payer signature, it is charged the network fee")
    final Stream<DynamicTest> chargesNetworkFeeToNodeThatSubmitsAuthorizedTransactionWithoutPayerSignature() {
        final var submittingNodeAccountId = asEntityString(4);
        return hapiTest(
                newKeyNamed("notTreasuryKey"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, submittingNodeAccountId, ONE_HBAR)),
                // Take a snapshot of the node's balance before submitting
                balanceSnapshot("preConsensus", submittingNodeAccountId),
                // Bypass ingest using a non-default node to submit a privileged transaction that claims
                // 0.0.2 as the payer, but signs with the wrong key
                nodeCreate("newNode")
                        .signedBy("notTreasuryKey")
                        .setNode(submittingNodeAccountId)
                        .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                // And verify that the node is charged the network fee for submitting this transaction
                getAccountBalance(submittingNodeAccountId).hasTinyBars(reducedFromSnapshot("preConsensus")));
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST, requirement = SYSTEM_ACCOUNT_BALANCES)
    @DisplayName("if a node submits an authorized transaction without payer signature, it is charged the network fee")
    final Stream<DynamicTest> payerSolvencyStillCheckedEvenForDuplicateTransaction() {
        final var submittingNodeAccountId = asEntityString(4);
        final AtomicLong preDuplicateBalance = new AtomicLong();
        return hapiTest(
                cryptoCreate(CIVILIAN),
                cryptoTransfer(tinyBarsFromTo(GENESIS, submittingNodeAccountId, ONE_HBAR)),
                usableTxnIdNamed(TXN_ID).payerId(CIVILIAN),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, ONE_HBAR))
                        .payingWith(CIVILIAN)
                        .txnId(TXN_ID),
                // Zero out the payer's balance before submitting a duplicate transaction
                getAccountBalance(CIVILIAN).exposingBalanceTo(preDuplicateBalance::set),
                sourcing(() -> cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, preDuplicateBalance.get()))),
                // Take a snapshot of the node's balance before submitting
                balanceSnapshot("preConsensus", submittingNodeAccountId),
                // Bypass ingest using a non-default node to submit a duplicate with the payer,
                // who now has a zero balance; notice that since this transaction id has already
                // been handled once successfully above, the getTransactionReceipt status will
                // appear to be SUCCESS
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, ONE_HBAR))
                        .setNode(submittingNodeAccountId)
                        .payingWith(CIVILIAN)
                        .txnId(TXN_ID),
                // Ensure a later transaction with a different id has been handled so the duplicate
                // record queried below is guaranteed to be in the record cache
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                getTxnRecord(TXN_ID)
                        .andAnyDuplicates()
                        .assertingNothingAboutHashes()
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasDuplicates(inOrder(recordWith().status(INSUFFICIENT_PAYER_BALANCE))),
                // And verify that the node is charged the network fee for submitting this transaction
                getAccountBalance(submittingNodeAccountId).hasTinyBars(reducedFromSnapshot("preConsensus")));
    }

    @HapiTest
    final Stream<DynamicTest> usesUnclassifiableIfNoClassifiableAvailable() {
        return hapiTest(
                newKeyNamed("wrongKey"),
                cryptoCreate(CIVILIAN),
                usableTxnIdNamed(TXN_ID).payerId(CIVILIAN),
                cryptoTransfer(tinyBarsFromTo(GENESIS, TO, ONE_HBAR)),
                uncheckedSubmit(
                        cryptoCreate("nope").payingWith(CIVILIAN).txnId(TXN_ID).signedBy("wrongKey")),
                sleepFor(MS_TO_WAIT_FOR_CONSENSUS),
                getReceipt(TXN_ID).hasPriorityStatus(INVALID_PAYER_SIGNATURE),
                getTxnRecord(TXN_ID)
                        .assertingNothingAboutHashes()
                        .hasPriority(recordWith()
                                .status(INVALID_PAYER_SIGNATURE)
                                .transfers(includingDeduction("node payment", TO))));
    }

    @HapiTest
    final Stream<DynamicTest> classifiableTakesPriorityOverUnclassifiable() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(100 * 100_000_000L),
                usableTxnIdNamed(TXN_ID).payerId(CIVILIAN),
                cryptoTransfer(tinyBarsFromTo(GENESIS, TO, 100_000_000L)),
                uncheckedSubmit(cryptoCreate("nope")
                                .txnId(TXN_ID)
                                .payingWith(CIVILIAN)
                                .setNode(asEntityString(4)))
                        .logged(),
                uncheckedSubmit(
                        cryptoCreate("sure").txnId(TXN_ID).payingWith(CIVILIAN).setNode(TO)),
                sleepFor(MS_TO_WAIT_FOR_CONSENSUS),
                getReceipt(TXN_ID)
                        .andAnyDuplicates()
                        .logged()
                        .hasPriorityStatus(SUCCESS)
                        .hasDuplicateStatuses(INVALID_NODE_ACCOUNT),
                getTxnRecord(TXN_ID)
                        .assertingNothingAboutHashes()
                        .andAnyDuplicates()
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasDuplicates(inOrder(recordWith().status(INVALID_NODE_ACCOUNT))));
    }
}
