// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.transaction.TransactionBody;
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
}
