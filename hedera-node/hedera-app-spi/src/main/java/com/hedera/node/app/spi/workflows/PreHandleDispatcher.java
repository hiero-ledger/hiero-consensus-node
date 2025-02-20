// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code PreHandleDispatcher} takes a validated transaction and dispatches it to the correct handler
 *
 * @deprecated This interface is not needed anymore
 */
@Deprecated(forRemoval = true)
@FunctionalInterface
public interface PreHandleDispatcher {
    /**
     * Dispatch a request. It is forwarded to the correct handler, which takes care of the specific functionality
     *
     * @param context the {@link PreHandleContext} for the dispatched transaction
     * @throws NullPointerException if {@code transactionBody} is {@code null}
     * @throws PreCheckException if the transaction within the context is invalid
     */
    void dispatch(@NonNull PreHandleContext context) throws PreCheckException;
}
