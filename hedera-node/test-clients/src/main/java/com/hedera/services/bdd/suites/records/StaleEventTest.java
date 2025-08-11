package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.EmbeddedReason.MANIPULATES_WORKFLOW;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GRPC_PROXY_ENDPOINT_FQDN;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hederahashgraph.api.proto.java.Response;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.meta.HapiGetReceipt;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.security.cert.CertificateEncodingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class StaleEventTest {

    @EmbeddedHapiTest(MANIPULATES_WORKFLOW)
    final Stream<DynamicTest> transactionFromStaleEventCanBeResubmitted() {
        return hapiTest(
                newKeyNamed("alice"),
                cryptoCreate("alice"),
                doingContextual(spec -> {
                    final var transfer = new HapiCryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(DEFAULT_PAYER, "alice", ONE_HUNDRED_HBARS))
                            .payingWith(DEFAULT_PAYER);
                    try {
                        final Transaction txn = transfer.signedTxnFor(spec);
                        final var embeddedNetwork = (EmbeddedNetwork) spec.targetNetworkOrThrow();
                        // Run through ingest only to populate the DeduplicationCache, we will expect a receipt query with OK status and UNKNOWN
                        final TransactionBody txnBody = TransactionBody.PROTOBUF.parse(
                                SignedTransaction.PROTOBUF.parse(
                                        Bytes.wrap(txn.getSignedTransactionBytes().toByteArray())).bodyBytes());
                        TransactionID txnID = TransactionID.parseFrom(com.hedera.hapi.node.base.TransactionID.PROTOBUF.toBytes(txnBody.transactionID())
                                .toByteArray());

                        embeddedNetwork.embeddedHederaOrThrow().markTransactionStale(txn);
                        System.out.println("SignedTransaction: " + txn.getSignedTransactionBytes());
                        // Execute the HapiCryptoTransfer, it should be silently ignored in a stale FakeEvent however,
                        // it will be added to the DeduplicationCache
                        transfer.execFor(spec);

                        HapiGetReceipt hapiGetReceipt = getReceipt(txnID).hasAnswerOnlyPrecheck(ResponseCodeEnum.OK).hasPriorityStatus(ResponseCodeEnum.UNKNOWN);
                        hapiGetReceipt.execFor(spec);
                        System.out.println("Response : " + hapiGetReceipt.getResponse());

                        // Now inject a stale event containing the same txn; expect receipt query to return TRANSACTION_IN_STALE_EVENT, client can resubmit the transaction
                        embeddedNetwork.embeddedHederaOrThrow().injectStaleEventForTransaction(txn);
                        HapiGetReceipt nextReceipt = getReceipt(txnID).hasAnswerOnlyPrecheck(ResponseCodeEnum.OK).hasPriorityStatus(ResponseCodeEnum.TRANSACTION_IN_STALE_EVENT);
                        nextReceipt.execFor(spec);
                        System.out.println("Response : " + nextReceipt.getResponse());
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }));
    }

}
