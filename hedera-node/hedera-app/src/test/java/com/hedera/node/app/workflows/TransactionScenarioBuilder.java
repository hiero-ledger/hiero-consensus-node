// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.AtomicBatchTransactionBody;
import com.hedera.node.app.spi.fixtures.Scenarios;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A very useful class for generating TransactionInfo's that can be used for testing.
 */
public class TransactionScenarioBuilder implements Scenarios {
    private TransactionBody body;
    private HederaFunctionality function;

    public TransactionScenarioBuilder() {
        this(goodDefaultBody(), HederaFunctionality.CRYPTO_TRANSFER);
    }

    public TransactionScenarioBuilder(
            @NonNull final TransactionBody body, @NonNull final HederaFunctionality function) {
        this.body = requireNonNull(body);
        this.function = function;
    }

    public static TransactionScenarioBuilder scenario() {
        return new TransactionScenarioBuilder();
    }

    public TransactionScenarioBuilder withTransactionID(@Nullable final TransactionID transactionID) {
        body = body.copyBuilder().transactionID(transactionID).build();
        return this;
    }

    public TransactionScenarioBuilder withNodeAccount(@Nullable final AccountID nodeAccount) {
        body = body.copyBuilder().nodeAccountID(nodeAccount).build();
        return this;
    }

    public TransactionScenarioBuilder withPayer(@Nullable final AccountID payer) {
        final var oldId = body.transactionID();
        final var id = oldId == null
                ? TransactionID.newBuilder().accountID(payer).build()
                : body.transactionID().copyBuilder().accountID(payer).build();
        return withTransactionID(id);
    }

    public TransactionScenarioBuilder withTransactionValidStart(@Nullable final Timestamp transactionValidStart) {
        final var oldId = body.transactionID();
        final var id = oldId == null
                ? TransactionID.newBuilder()
                        .transactionValidStart(transactionValidStart)
                        .build()
                : body.transactionID()
                        .copyBuilder()
                        .transactionValidStart(transactionValidStart)
                        .build();
        return withTransactionID(id);
    }

    public TransactionScenarioBuilder withTransactionValidDuration(@Nullable final Duration transactionValidDuration) {
        body = body.copyBuilder()
                .transactionValidDuration(transactionValidDuration)
                .build();
        return this;
    }

    public TransactionScenarioBuilder withTransactionFee(final long transactionFee) {
        body = body.copyBuilder().transactionFee(transactionFee).build();
        return this;
    }

    public TransactionScenarioBuilder withMemo(@Nullable final String memo) {
        body = body.copyBuilder().memo(memo).build();
        return this;
    }

    public TransactionScenarioBuilder withCryptoTransfer(@Nullable final CryptoTransferTransactionBody op) {
        body = body.copyBuilder().cryptoTransfer(op).build();
        return this;
    }

    public TransactionScenarioBuilder withBatchKey(@Nullable final Key key) {
        body = body.copyBuilder().batchKey(key).build();
        return this;
    }

    @NonNull
    public TransactionInfo txInfo() {
        final var signedbytes = asBytes(TransactionBody.PROTOBUF, body);
        final var signedTx =
                SignedTransaction.newBuilder().bodyBytes(signedbytes).build();
        return new TransactionInfo(signedTx, body, SignatureMap.DEFAULT, signedbytes, function, null);
    }

    @NonNull
    public TransactionInfo txInfoForBatch() {
        final var innerBody =
                goodDefaultBody().copyBuilder().batchKey(Key.DEFAULT).build();
        final var innerTxns = List.of(Transaction.newBuilder()
                .body(innerBody)
                .signedTransactionBytes(asBytes(
                        SignedTransaction.PROTOBUF,
                        SignedTransaction.newBuilder()
                                .bodyBytes(asBytes(TransactionBody.PROTOBUF, innerBody))
                                .build()))
                .build());
        final var atomicBody = TransactionBody.newBuilder()
                .nodeAccountID(NODE_1.nodeAccountID())
                .transactionID(TransactionID.newBuilder()
                        .accountID(ALICE.accountID())
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds((System.currentTimeMillis() / 1000) - 1)
                                .build())
                        .build())
                .atomicBatch(AtomicBatchTransactionBody.newBuilder()
                        .transactions(
                                innerTxns.stream().map(Transaction::bodyBytes).toList()))
                .transactionFee(1L)
                .transactionValidDuration(Duration.newBuilder().seconds(60).build())
                .build();
        final var signedbytes = asBytes(TransactionBody.PROTOBUF, atomicBody);
        final var signedTx =
                SignedTransaction.newBuilder().bodyBytes(signedbytes).build();
        return new TransactionInfo(
                signedTx, atomicBody, SignatureMap.DEFAULT, signedbytes, HederaFunctionality.ATOMIC_BATCH, null);
    }

    public static TransactionBody goodDefaultBody() {
        return TransactionBody.newBuilder()
                .nodeAccountID(NODE_1.nodeAccountID())
                .transactionID(TransactionID.newBuilder()
                        .accountID(ALICE.accountID())
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds((System.currentTimeMillis() / 1000) - 1)
                                .build())
                        .build())
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(
                                        AccountAmount.newBuilder()
                                                .accountID(ALICE.accountID())
                                                .amount(-10)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(BOB.accountID())
                                                .amount(10)
                                                .build())
                                .build())
                        .build())
                .transactionFee(1L)
                .transactionValidDuration(Duration.newBuilder().seconds(60).build())
                .build();
    }
}
