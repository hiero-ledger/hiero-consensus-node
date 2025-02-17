/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.workflows.purechecks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementation of {@link PureChecksContext}.
 */
public class PureChecksContextImpl implements PureChecksContext {
    /**
     * The transaction body.
     */
    private final TransactionBody txn;

    /**
     * Configuration to be used during pre-handle
     */
    private final Configuration configuration;

    private final TransactionDispatcher dispatcher;
    private final TransactionChecker transactionChecker;

    /**
     * Create a new instance of {@link PureChecksContextImpl}.
     * @throws PreCheckException if the payer account does not exist
     */
    public PureChecksContextImpl(
            @NonNull final TransactionBody txn,
            @NonNull final Configuration configuration,
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final TransactionChecker transactionChecker)
            throws PreCheckException {
        this.txn = requireNonNull(txn, "txn must not be null!");
        this.configuration = requireNonNull(configuration, "configuration must not be null!");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null!");
        this.transactionChecker = requireNonNull(transactionChecker, "transactionChecker must not be null!");
    }

    @NonNull
    @Override
    public TransactionBody body() {
        return txn;
    }

    @NonNull
    @Override
    public Configuration configuration() {
        return configuration;
    }
}
