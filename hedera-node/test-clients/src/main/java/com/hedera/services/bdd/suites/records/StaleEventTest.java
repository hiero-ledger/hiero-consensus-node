// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.EmbeddedReason.MANIPULATES_WORKFLOW;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.STALE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.spec.queries.meta.HapiGetReceipt;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests that a transaction submitted to a node can be resubmitted after it has gone through ingest, submitted to
 * the platform, the event becomes stale, and then app receives a stale event notification.
 */
public class StaleEventTest {

    @EmbeddedHapiTest(MANIPULATES_WORKFLOW)
    final Stream<DynamicTest> transactionFromStaleEventCanBeResubmitted() {
        return hapiTest(
                newKeyNamed("alice"),
                cryptoCreate("alice").balance(ONE_MILLION_HBARS),
                usableTxnIdNamed("txnId").payerId("alice"),
                doingContextual(spec -> {
                    final var transfer = new HapiCryptoTransfer(
                                    HapiCryptoTransfer.tinyBarsFromTo("alice", FUNDING, ONE_HUNDRED_HBARS))
                            .signedBy("alice")
                            .txnId("txnId")
                            .fireAndForget();
                    try {
                        final var embeddedNetwork = (EmbeddedNetwork) spec.targetNetworkOrThrow();
                        embeddedNetwork.embeddedHederaOrThrow().considerAllEventsStale(true);
                        TransactionID txId = spec.registry().getTxnId("txnId");
                        // Execute the HapiCryptoTransfer, it should be silently ignored in a stale FakeEvent however,
                        // it will be added to the DeduplicationCache
                        transfer.execFor(spec);

                        Thread.sleep(Duration.ofSeconds(1));

                        embeddedNetwork.embeddedHederaOrThrow().considerAllEventsStale(false);

                        HapiGetReceipt hapiGetReceipt = getReceipt(txId);
                        hapiGetReceipt.execFor(spec);
                        assertEquals(
                                ResponseCodeEnum.UNKNOWN,
                                hapiGetReceipt
                                        .getResponse()
                                        .getTransactionGetReceipt()
                                        .getReceipt()
                                        .getStatus());

                        // Now inject a stale event containing the same txn; expect receipt query to return
                        // STALE, client can resubmit the transaction
                        embeddedNetwork
                                .embeddedHederaOrThrow()
                                .triggerStaleEventCallbackForTransaction(transfer.getSubmittedTransaction());
                        HapiGetReceipt nextReceipt = getReceipt("txnId");
                        nextReceipt.execFor(spec);

                        assertEquals(
                                STALE,
                                nextReceipt
                                        .getResponse()
                                        .getTransactionGetReceipt()
                                        .getReceipt()
                                        .getStatus());

                        // Now resubmit the transaction, it should succeed
                        transfer.execFor(spec);

                        Thread.sleep(Duration.ofSeconds(1));
                        HapiGetReceipt finalReceipt = getReceipt("txnId");
                        finalReceipt.execFor(spec);
                        assertEquals(
                                SUCCESS,
                                finalReceipt
                                        .getResponse()
                                        .getTransactionGetReceipt()
                                        .getReceipt()
                                        .getStatus());
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }));
    }
}
