// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.asBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.junit.jupiter.api.Test;

class TransactionPartsTest {

    @Test
    void fromBlockItemWithSignedTransaction() {
        final var protoTxBody = com.hederahashgraph.api.proto.java.TransactionBody.newBuilder()
                .setMemo("test-memo")
                .setCryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                        .setInitialBalance(100L)
                        .build())
                .build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(Bytes.wrap(protoTxBody.toByteArray()))
                .build();
        final var blockItem = BlockItem.newBuilder()
                .signedTransaction(Bytes.wrap(asBytes(SignedTransaction.PROTOBUF, signedTx)))
                .build();

        final var parts = TransactionParts.from(blockItem);

        assertNotNull(parts);
        assertEquals("test-memo", parts.body().getMemo());
        assertEquals(HederaFunctionality.CryptoCreate, parts.function());
        assertEquals(100L, parts.body().getCryptoCreateAccount().getInitialBalance());
    }
}
