// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for node-originated transaction submissions to the network within an {@link AppContext}
 * using a given executor. Not TSS-specific: it is the shared parent for {@link TssSubmissions} and
 * {@link com.hedera.node.app.workflows.clpr.ClprSubmissions}, each of which supplies its own
 * {@link #retryEnvelope(Configuration) retry envelope} (TSS reads {@code NetworkAdminConfig}; CLPR
 * reads its {@code clpr.manifestSubmission*} knobs).
 */
public abstract class ConsensusSubmissions {
    private static final Logger log = LogManager.getLogger(ConsensusSubmissions.class);

    protected final Executor executor;
    protected final AppContext appContext;

    protected ConsensusSubmissions(@NonNull final Executor executor, @NonNull final AppContext appContext) {
        this.executor = requireNonNull(executor);
        this.appContext = requireNonNull(appContext);
    }

    /**
     * The submission retry envelope (attempts, distinct transaction ids to try, and delay between
     * attempts) this submitter should use, read from the given resolved configuration.
     */
    protected abstract RetryEnvelope retryEnvelope(@NonNull Configuration config);

    /** Retry parameters for {@link #submitIfActive}. */
    public record RetryEnvelope(
            int timesToTry, int distinctTxnIds, @NonNull Duration retryDelay) {}

    /**
     * Attempts to submit a transaction to the network if node gossip is available, retrying based on the
     * concrete submitter's {@link #retryEnvelope(Configuration)}.
     * <p>
     * Returns a future that completes when the transaction has been submitted; or completes exceptionally
     * if the transaction could not be submitted after the configured number of retries.
     *
     * @param spec the spec to build the transaction to submit
     * @param onFailure a consumer to call if the transaction fails to submit
     * @return a future that completes when the transaction has been submitted, exceptionally if it was not
     */
    protected CompletableFuture<Void> submitIfActive(
            @NonNull final Consumer<TransactionBody.Builder> spec,
            @NonNull final BiConsumer<TransactionBody, String> onFailure) {
        // Best-effort: never try to submit anything if node gossip is unavailable (e.g. REPLAYING_EVENTS).
        if (!appContext.gossip().isAvailable()) {
            log.info("Skipping submission because gossip is unavailable");
            return CompletableFuture.completedFuture(null);
        }
        final var selfId = appContext.selfNodeInfoSupplier().get().accountId();
        final var consensusNow = appContext.instantSource().instant();
        final var config = appContext.configSupplier().get();
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        final var envelope = retryEnvelope(config);
        return appContext
                .gossip()
                .submitFuture(
                        selfId,
                        consensusNow,
                        Duration.of(hederaConfig.transactionMaxValidDuration(), SECONDS),
                        spec,
                        executor,
                        envelope.timesToTry(),
                        envelope.distinctTxnIds(),
                        envelope.retryDelay(),
                        onFailure);
    }
}
