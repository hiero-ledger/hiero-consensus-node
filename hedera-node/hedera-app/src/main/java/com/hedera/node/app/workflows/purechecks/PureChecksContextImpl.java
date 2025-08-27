// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.purechecks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementation of {@link PureChecksContext}.
 */
public class PureChecksContextImpl implements PureChecksContext {
    /**
     * The transaction body.
     */
    private final TransactionBody txn;

    private final TransactionDispatcher dispatcher;

    /**
     * Create a new instance of {@link PureChecksContextImpl}.
     * @throws PreCheckException if the payer account does not exist
     */
    public PureChecksContextImpl(@NonNull final TransactionBody txn, @NonNull final TransactionDispatcher dispatcher)
            throws PreCheckException {
        this.txn = requireNonNull(txn, "txn must not be null!");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null!");
    }

    @NonNull
    @Override
    public TransactionBody body() {
        return txn;
    }

    @Override
    public void dispatchPureChecks(@NonNull TransactionBody body) throws PreCheckException {
        final var pureChecksContext = new PureChecksContextImpl(body, dispatcher);
        dispatcher.dispatchPureChecks(pureChecksContext);
    }
}
