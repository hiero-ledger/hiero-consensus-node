// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.swirlds.config.api.Configuration;
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
     * Returns the current {@link Configuration}.
     *
     * @return the {@link Configuration}
     */
    @NonNull
    Configuration configuration();
}
