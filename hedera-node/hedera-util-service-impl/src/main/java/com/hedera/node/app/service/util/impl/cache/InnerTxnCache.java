// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;

public class InnerTxnCache {
    private static final int INNER_TXN_CACHE_TIMEOUT = 15;
    private final LoadingCache<Bytes, TransactionBody> transactionsCache;

    public InnerTxnCache(
            @NonNull final TransactionParser transactionParser, @NonNull final Configuration configuration) {
        transactionsCache = Caffeine.newBuilder()
                .expireAfterWrite(INNER_TXN_CACHE_TIMEOUT, TimeUnit.SECONDS)
                .build(transactionBytes -> transactionParser.parse(transactionBytes, configuration));
    }

    /**
     * @param bytes the bytes we want to parse
     * @return the parsed SignedTransaction
     */
    public TransactionBody computeIfAbsent(@NonNull Bytes bytes) throws PreCheckException {
        return transactionsCache.get(bytes);
    }

    /**
     * @param bytes the bytes we want to parse
     * @return the parsed SignedTransaction
     */
    public TransactionBody computeIfAbsentUnchecked(@NonNull Bytes bytes) {
        return transactionsCache.get(bytes);
    }
}
