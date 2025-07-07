// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.service.consensus.impl.handlers.ConsensusSubmitMessageHandler.noThrowSha384HashOf;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.contexts.BaseOpContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class TranslationContextTest {
    @Test
    void hashIsOfSignedTransactionBytesIfSet() {
        final var signedTx =
                SignedTransaction.newBuilder().bodyBytes(Bytes.fromHex("0123")).build();
        final var serializedSignedTx = SignedTransaction.PROTOBUF.toBytes(signedTx);

        final var subject = new BaseOpContext(
                "",
                ExchangeRateSet.DEFAULT,
                TransactionID.DEFAULT,
                signedTx,
                HederaFunctionality.NONE,
                serializedSignedTx);

        assertEquals(Bytes.wrap(noThrowSha384HashOf(serializedSignedTx.toByteArray())), subject.transactionHash());
    }

    @Test
    void hashIsOfSerializedTransactionIfMissingSignedTransactionBytes() {
        final var transactionBytes = Transaction.PROTOBUF.toBytes(Transaction.DEFAULT);

        final var subject = new BaseOpContext(
                "",
                ExchangeRateSet.DEFAULT,
                TransactionID.DEFAULT,
                SignedTransaction.DEFAULT,
                HederaFunctionality.NONE,
                null);

        assertEquals(Bytes.wrap(noThrowSha384HashOf(transactionBytes.toByteArray())), subject.transactionHash());
    }
}
