// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * A transaction that reaches consensus without any node's ingest checks (submitted via a non-default node in
 * embedded mode) and names a payer that did not sign it must be charged to the submitting node as a due-diligence
 * failure, whether or not the payer account is hollow.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class HollowPayerDueDiligenceTest {
    private static final String BYPASS_NODE = "4";
    private static final String WRONG_KEY = "wrongKey";

    @RepeatableHapiTest({MUST_SKIP_INGEST, NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> unsignedNonHollowPayerPastIngestIsChargedToSubmittingNode() {
        return unsignedPayerPastIngestIsChargedToSubmittingNode(
                "civilian", cryptoCreate("civilian").balance(ONE_HUNDRED_HBARS));
    }

    @RepeatableHapiTest({MUST_SKIP_INGEST, NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> unsignedHollowPayerPastIngestIsChargedToSubmittingNode() {
        return unsignedPayerPastIngestIsChargedToSubmittingNode("hollowPayer", createHollow(1, i -> "hollowPayer"));
    }

    /** Control: the same forged transaction sent through the default node is stopped by that node's ingest. */
    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW})
    final Stream<DynamicTest> unsignedHollowPayerIsRejectedByHonestIngest() {
        final var payerBefore = new AtomicLong();
        return hapiTest(
                newKeyNamed(WRONG_KEY),
                createHollow(1, i -> "hollowPayer"),
                getAccountBalance("hollowPayer").exposingBalanceTo(payerBefore::set),
                cryptoCreate("nope")
                        .payingWith("hollowPayer")
                        .signedBy(WRONG_KEY)
                        .hasPrecheck(ResponseCodeEnum.INVALID_SIGNATURE),
                sourcing(() -> getAccountBalance("hollowPayer").hasTinyBars(payerBefore.get())));
    }

    /** Control: a hollow payer on a batch INNER transaction, with no alias signature, is also stopped at ingest. */
    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW})
    final Stream<DynamicTest> unsignedHollowInnerPayerIsRejectedByHonestIngest() {
        final var payerBefore = new AtomicLong();
        return hapiTest(
                newKeyNamed(WRONG_KEY),
                newKeyNamed("batchKey"),
                cryptoCreate("operator").balance(ONE_HUNDRED_HBARS),
                createHollow(1, i -> "hollowPayer"),
                getAccountBalance("hollowPayer").exposingBalanceTo(payerBefore::set),
                atomicBatch(cryptoCreate("nope")
                                .payingWith("hollowPayer")
                                .signedBy(WRONG_KEY)
                                .batchKey("batchKey"))
                        .payingWith("operator")
                        .signedBy("operator", "batchKey")
                        .hasPrecheck(ResponseCodeEnum.INVALID_SIGNATURE),
                sourcing(() -> getAccountBalance("hollowPayer").hasTinyBars(payerBefore.get())));
    }

    /** Repeated forgeries against a hollow payer never debit it and never finalize it; each is charged to the node. */
    @RepeatableHapiTest({MUST_SKIP_INGEST, NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW})
    final Stream<DynamicTest> repeatedForgeriesAgainstHollowPayerNeverDebitIt() {
        final var balances = new ArrayList<Long>();
        final var ops = new ArrayList<SpecOperation>();
        ops.add(newKeyNamed(WRONG_KEY));
        ops.add(cryptoTransfer(tinyBarsFromTo(GENESIS, BYPASS_NODE, ONE_HUNDRED_HBARS)));
        ops.add(createHollow(1, i -> "hollowPayer"));
        ops.add(getAccountBalance("hollowPayer").exposingBalanceTo(balances::add));
        for (int i = 0; i < 3; i++) {
            ops.add(cryptoCreate("nope" + i)
                    .payingWith("hollowPayer")
                    .signedBy(WRONG_KEY)
                    .setNode(BYPASS_NODE)
                    .hasAnyStatusAtAll());
            ops.add(getAccountBalance("hollowPayer").exposingBalanceTo(balances::add));
        }
        ops.add(getAccountInfo("hollowPayer").isHollow());
        ops.add(withOpContext((spec, opLog) -> {
            opLog.info("hollowPayer balances across forged txns: {}", balances);
            for (int i = 1; i < balances.size(); i++) {
                assertEquals(balances.get(0), balances.get(i), "hollow payer must not be debited by forgery #" + i);
            }
        }));
        return hapiTest(ops.toArray(SpecOperation[]::new));
    }

    /** Control: a schedule naming a hollow account as payer cannot be created, so it never reaches execution. */
    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW})
    final Stream<DynamicTest> scheduleWithHollowPayerIsNotCreatable() {
        final var payerBefore = new AtomicLong();
        return hapiTest(
                cryptoCreate("receiver"),
                createHollow(1, i -> "hollowPayer"),
                getAccountBalance("hollowPayer").exposingBalanceTo(payerBefore::set),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo(GENESIS, "receiver", 1L)))
                        .payingWith(GENESIS)
                        .designatingPayer("hollowPayer")
                        .hasKnownStatus(ResponseCodeEnum.INVALID_ACCOUNT_ID),
                sourcing(() -> getAccountBalance("hollowPayer").hasTinyBars(payerBefore.get())));
    }

    private Stream<DynamicTest> unsignedPayerPastIngestIsChargedToSubmittingNode(
            final String payer, final SpecOperation createPayer) {
        final var payerBefore = new AtomicLong();
        final var nodeBefore = new AtomicLong();
        final var payerAfter = new AtomicLong();
        final var nodeAfter = new AtomicLong();
        final var status = new AtomicReference<ResponseCodeEnum>();
        final var nodeDueBefore = new AtomicLong();
        final var nodeDueAfter = new AtomicLong();
        final var txn = payer + "Forged";
        return hapiTest(
                newKeyNamed(WRONG_KEY),
                cryptoTransfer(tinyBarsFromTo(GENESIS, BYPASS_NODE, ONE_HUNDRED_HBARS)),
                createPayer,
                nodePaymentsFor(nodeDueBefore),
                getAccountBalance(payer).exposingBalanceTo(payerBefore::set),
                getAccountBalance(BYPASS_NODE).exposingBalanceTo(nodeBefore::set),
                // Signed only by an unrelated key, so the signature map is non-empty but has nothing for the payer
                cryptoCreate("nope")
                        .payingWith(payer)
                        .signedBy(WRONG_KEY)
                        .setNode(BYPASS_NODE)
                        .via(txn)
                        .hasAnyStatusAtAll(),
                getTxnRecord(txn)
                        .assertingNothingAboutHashes()
                        .exposingTo(record -> status.set(record.getReceipt().getStatus()))
                        .logged(),
                nodePaymentsFor(nodeDueAfter),
                getAccountBalance(payer).exposingBalanceTo(payerAfter::set),
                getAccountBalance(BYPASS_NODE).exposingBalanceTo(nodeAfter::set),
                withOpContext((spec, opLog) -> {
                    opLog.info(
                            "payer={} status={} payerDelta={} nodeBalanceDelta={} nodeFeesDueDelta={}",
                            payer,
                            status.get(),
                            payerAfter.get() - payerBefore.get(),
                            nodeAfter.get() - nodeBefore.get(),
                            nodeDueAfter.get() - nodeDueBefore.get());
                    assertEquals(ResponseCodeEnum.INVALID_PAYER_SIGNATURE, status.get(), "status");
                    assertEquals(payerBefore.get(), payerAfter.get(), "payer balance must be unchanged");
                    assertEquals(true, nodeAfter.get() < nodeBefore.get(), "submitting node must be charged");
                }));
    }

    /** Reads the fees currently accrued to the bypass node in the NodePayments singleton (flushed at block boundaries). */
    private static SpecOperation nodePaymentsFor(final AtomicLong sink) {
        return blockingOrder(
                sleepForBlockPeriod(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 1L)),
                EmbeddedVerbs.<NodePayments>viewSingleton(
                        TokenService.NAME,
                        NODE_PAYMENTS_STATE_ID,
                        nodePayments -> sink.set(nodePayments.payments().stream()
                                .filter(p -> p.nodeAccountIdOrThrow().accountNumOrElse(0L) == 4L)
                                .mapToLong(NodePayment::fees)
                                .sum())));
    }
}
