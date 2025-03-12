// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class InnerTxnCache {
    private static final int INNER_TXN_CACHE_TIMEOUT = 15;

    private final LoadingCache<Bytes, TransactionBody> transactionsCache;

    /**
     * Default constructor for injection
     */
    @Inject
    public InnerTxnCache(Function<Bytes, TransactionBody> transactionParser) {
        transactionsCache = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(INNER_TXN_CACHE_TIMEOUT, TimeUnit.SECONDS)
                .build(new CacheLoader<>() {
                    @Override
                    public @NonNull TransactionBody load(@NonNull Bytes key) {
                        return transactionParser.apply(key);
                    }
                });
    }

    /**
     * @param bytes the bytes we want to parse
     * @return the parsed SignedTransaction
     */
    public TransactionBody computeIfAbsent(@NonNull Bytes bytes) {
        return transactionsCache.getUnchecked(bytes);
    }
}
