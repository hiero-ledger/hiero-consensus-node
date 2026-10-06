// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.annotations.CommonExecutor;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Submits startup migration root-hash votes to the network via gossip.
 */
@Singleton
public class MigrationRootHashSubmissions {
    private static final Logger log = LogManager.getLogger(MigrationRootHashSubmissions.class);

    private final ExecutorService executor;
    private final AppContext appContext;
    private final BiConsumer<TransactionBody, String> onFailure =
            (body, reason) -> log.warn("Failed to submit {} ({})", body, reason);

    @Inject
    public MigrationRootHashSubmissions(
            @NonNull @CommonExecutor final ExecutorService executor, @NonNull final AppContext appContext) {
        this.executor = requireNonNull(executor);
        this.appContext = requireNonNull(appContext);
    }

    /**
     * Attempts to submit a startup migration root-hash vote.
     *
     * @param op the migration root hash vote operation
     * @return {@code true} if gossip was active and a submission was initiated, otherwise {@code false}
     */
    public boolean submitStartupVoteIfActive(@NonNull final MigrationRootHashVoteTransactionBody op) {
        requireNonNull(op);
        return submitIfActive(
                b -> b.memo("Migration wrapped record root hash vote").migrationRootHashVote(op),
                onFailure,
                "startup migration root hash vote");
    }

    private boolean submitIfActive(
            @NonNull final Consumer<TransactionBody.Builder> spec,
            @NonNull final BiConsumer<TransactionBody, String> onFailure,
            @NonNull final String purpose) {
        requireNonNull(spec);
        requireNonNull(onFailure);
        requireNonNull(purpose);
        log.info("Submitting {} via gossip", purpose);
        if (!appContext.gossip().isAvailable()) {
            log.info("Skipping {} submission because gossip is unavailable", purpose);
            return false;
        }
        final var selfId = appContext.selfNodeInfoSupplier().get().accountId();
        final var consensusNow = appContext.instantSource().instant();
        final var config = appContext.configSupplier().get();
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        appContext
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
        return true;
    }
}
