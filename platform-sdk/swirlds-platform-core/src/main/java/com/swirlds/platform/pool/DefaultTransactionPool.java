/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.pool;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;

/**
 * A default implementation of a {@link TransactionPool}.
 */
public class DefaultTransactionPool implements TransactionPool {

    private final TransactionPoolNexus transactionPoolNexus;

    /**
     * Constructor.
     *
     * @param transactionPoolNexus the transaction pool nexus
     */
    public DefaultTransactionPool(@NonNull final TransactionPoolNexus transactionPoolNexus) {
        this.transactionPoolNexus = Objects.requireNonNull(transactionPoolNexus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitSystemTransaction(@NonNull final Bytes payload) {
        Objects.requireNonNull(payload);
        transactionPoolNexus.submitTransaction(payload, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        Objects.requireNonNull(platformStatus);
        transactionPoolNexus.updatePlatformStatus(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        Objects.requireNonNull(duration);
        transactionPoolNexus.reportUnhealthyDuration(duration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        transactionPoolNexus.clear();
    }
}
