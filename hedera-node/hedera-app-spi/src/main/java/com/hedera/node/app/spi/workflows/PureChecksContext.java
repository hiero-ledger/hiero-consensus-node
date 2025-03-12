// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the context of a single {@code pureChecks()}-call.
 */
@SuppressWarnings("UnusedReturnValue")
public interface PureChecksContext {
    /**
     * Gets the {@link TransactionBody}
     *
     * @return the {@link TransactionBody} in this context
     */
    @NonNull
    TransactionBody body();

    /**
     * Dispatches {@link TransactionHandler#pureChecks(PureChecksContext)} for the given {@link TransactionBody}.
     * @param body
     * @throws PreCheckException
     */
    void dispatchPureChecks(@NonNull TransactionBody body) throws PreCheckException;

    /**
     * Parses the transaction bytes and returns the {@link TransactionBody}.
     *
     * @param bodyBytes the bytes of the transaction
     * @return the {@link TransactionBody} parsed from the bytes
     * @throws PreCheckException if the transaction is invalid
     */
    @NonNull
    TransactionBody parseSignedTransactionBytes(@NonNull Bytes bodyBytes) throws PreCheckException;
}
