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

package com.hedera.node.app.tss;

import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Base class for submitting node transactions to the network within an application context using a given executor.
 */
public class TssSubmissions {
    private final Executor executor;
    private final AppContext appContext;

    public TssSubmissions(@NonNull final Executor executor, @NonNull final AppContext appContext) {
        this.executor = requireNonNull(executor);
        this.appContext = requireNonNull(appContext);
    }

    /**
     * Attempts to submit a transaction to the network, retrying based on the given configuration.
     * <p>
     * Returns a future that completes when the transaction has been submitted; or completes exceptionally
     * if the transaction could not be submitted after the configured number of retries.
     *
     * @param spec the spec to build the transaction to submit
     * @param onFailure a consumer to call if the transaction fails to submit
     * @return a future that completes when the transaction has been submitted, exceptionally if it was not
     */
    protected CompletableFuture<Void> submit(
            @NonNull final Consumer<TransactionBody.Builder> spec,
            @NonNull final BiConsumer<TransactionBody, String> onFailure) {
        final var selfId = appContext.selfNodeInfoSupplier().get().accountId();
        final var consensusNow = appContext.instantSource().instant();
        final var config = appContext.configSupplier().get();
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        return appContext
                .gossip()
                .submitFuture(
                        selfId,
                        consensusNow,
                        Duration.of(hederaConfig.transactionMaxValidDuration(), SECONDS),
                        spec,
                        executor,
                        adminConfig.timesToTrySubmission(),
                        adminConfig.distinctTxnIdsToTry(),
                        adminConfig.retryDelay(),
                        onFailure);
    }
}
