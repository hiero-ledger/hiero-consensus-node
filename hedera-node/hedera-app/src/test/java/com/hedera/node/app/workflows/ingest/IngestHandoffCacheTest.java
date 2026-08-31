// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

final class IngestHandoffCacheTest {
    @Test
    void takeRemovesTheEntry() {
        final var cache = new IngestHandoffCache();
        final var bytes = Bytes.wrap(new byte[] {1, 2, 3});
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(2L).build())
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1L).build())
                        .build())
                .build();
        final var txInfo = new TransactionInfo(
                SignedTransaction.DEFAULT,
                txBody,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                HederaFunctionality.CRYPTO_TRANSFER,
                bytes);
        cache.put(bytes, new IngestHandoff(txInfo, null, null, 1L));

        assertThat(cache.take(bytes)).isNotNull();
        assertThat(cache.take(bytes)).isNull();
    }
}
