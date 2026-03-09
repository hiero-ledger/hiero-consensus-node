// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.synchronous;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.spi.records.RecordSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Registry of pending futures waiting for transaction records.
 * <p>
 * When a synchronous transaction is submitted, {@link #register(TransactionID)} creates a
 * {@link CompletableFuture} keyed on the transaction ID. When {@code HandleWorkflow} completes
 * the transaction, it calls {@link #complete(TransactionID, RecordSource)} to unblock the waiting
 * caller.
 */
@Singleton
public class PendingFutureRegistry {

    private final ConcurrentHashMap<TransactionID, CompletableFuture<RecordSource>> pending = new ConcurrentHashMap<>();

    @Inject
    public PendingFutureRegistry() {
        // Dagger-injected singleton
    }

    /**
     * Register a new pending future for the given transaction ID.
     *
     * @param txnId the transaction ID to register
     * @return a {@link CompletableFuture} that will be completed when the record is available
     */
    @NonNull
    public CompletableFuture<RecordSource> register(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        final var future = new CompletableFuture<RecordSource>();
        pending.put(txnId, future);
        return future;
    }

    /**
     * Complete the future for the given transaction ID with the provided record source.
     * If no future is registered for this ID (e.g., a non-synchronous transaction), this is a no-op.
     *
     * @param txnId  the transaction ID
     * @param source the record source containing the transaction record
     */
    public void complete(@NonNull final TransactionID txnId, @NonNull final RecordSource source) {
        requireNonNull(txnId);
        requireNonNull(source);
        final var future = pending.remove(txnId);
        if (future != null) {
            future.complete(source);
        }
    }

    /**
     * Fail the future for the given transaction ID with the provided cause.
     * If no future is registered for this ID, this is a no-op.
     *
     * @param txnId the transaction ID
     * @param cause the failure cause
     */
    public void fail(@NonNull final TransactionID txnId, @NonNull final Throwable cause) {
        requireNonNull(txnId);
        requireNonNull(cause);
        final var future = pending.remove(txnId);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }
}
