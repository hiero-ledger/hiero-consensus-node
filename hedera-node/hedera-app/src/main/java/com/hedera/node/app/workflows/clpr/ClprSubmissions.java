// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.clpr.ClprEndpointPublicationTransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.tss.ConsensusSubmissions;
import com.hedera.node.config.data.ClprConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Submission helper for node-originated CLPR HAPI transactions. Extends the neutral
 * {@link ConsensusSubmissions} base (shared with {@code TssSubmissions}) to reuse its
 * gossip-availability check and retry loop, and supplies the CLPR-specific retry envelope
 * ({@code clpr.manifestSubmissionRetries / manifestSubmissionRetryDelay / manifestSubmissionDistinctTxnIds},
 * design §8.2). The CLPR defaults mirror {@code NetworkAdminConfig}'s (design §8.3), so day-one
 * behavior matches the TSS envelope while remaining independently tunable.
 */
@Singleton
public class ClprSubmissions extends ConsensusSubmissions {
    private static final Logger log = LogManager.getLogger(ClprSubmissions.class);

    private final BiConsumer<TransactionBody, String> onFailure =
            (body, reason) -> log.warn("Failed to submit {} ({})", body, reason);

    @Inject
    public ClprSubmissions(@NonNull final ExecutorService executor, @NonNull final AppContext appContext) {
        super(executor, appContext);
    }

    @Override
    protected RetryEnvelope retryEnvelope(@NonNull final Configuration config) {
        final var clprConfig = config.getConfigData(ClprConfig.class);
        return new RetryEnvelope(
                clprConfig.manifestSubmissionRetries(),
                clprConfig.manifestSubmissionDistinctTxnIds(),
                clprConfig.manifestSubmissionRetryDelay());
    }

    /**
     * Submits a CLPR endpoint self-publication with the node's current endpoint (design §6).
     *
     * @param endpoint the node's advertised CLPR endpoint
     * @return a future that completes when the transaction has been submitted; completes
     *     exceptionally if the retry envelope is exhausted
     */
    public CompletableFuture<Void> submitEndpointPublication(@NonNull final ClprEndpoint endpoint) {
        requireNonNull(endpoint);
        if (!appContext.configSupplier().get().getConfigData(ClprConfig.class).enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        log.info("[Clpr] submitting endpoint self-publication for endpoint {}", endpoint);
        return submitIfActive(
                b -> b.clprEndpointPublication(ClprEndpointPublicationTransactionBody.newBuilder()
                        .endpoint(endpoint)
                        .build()),
                onFailure);
    }
}
