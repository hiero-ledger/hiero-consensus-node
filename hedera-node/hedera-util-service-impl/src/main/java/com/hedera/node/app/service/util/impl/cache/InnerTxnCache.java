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

/**
 * Cache for parsing and storing transaction bodies.
 * Provides methods to retrieve parsed transaction bodies from byte arrays,
 * with caching to avoid repeated parsing of the same transaction data.
 */
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
     * Retrieves a parsed transaction body from the cache or parses it if not present.
     * Will propagate any PreCheckException thrown during parsing.
     *
     * @param bytes the bytes representing the transaction to parse
     * @return the parsed TransactionBody
     * @throws PreCheckException if transaction validation fails during parsing
     */
    public TransactionBody computeIfAbsent(@NonNull Bytes bytes) throws PreCheckException {
        try {
            return transactionsCache.get(bytes);
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof PreCheckException) {
                throw (PreCheckException) cause;
            }
            throw e;
        }
    }

    /**
     * Retrieves a parsed transaction body from the cache or parses it if not present.
     * Does not propagate PreCheckException, instead wrapping it in a RuntimeException.
     *
     * @param bytes the bytes representing the transaction to parse
     * @return the parsed TransactionBody
     */
    public TransactionBody computeIfAbsentUnchecked(@NonNull Bytes bytes) {
        return transactionsCache.get(bytes);
    }
}
