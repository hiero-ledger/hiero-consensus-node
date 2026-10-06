// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WAITING_FOR_LEDGER_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads user transactions saved at a freeze and resubmits them through ingest once the node is {@code ACTIVE}.
 */
public class PendingTransactionsRestorer {
    private static final Logger logger = LogManager.getLogger(PendingTransactionsRestorer.class);

    // Rejections that may pass on a later attempt
    private static final Set<ResponseCodeEnum> RETRYABLE =
            EnumSet.of(BUSY, PLATFORM_NOT_ACTIVE, PLATFORM_TRANSACTION_NOT_CREATED, WAITING_FOR_LEDGER_ID);

    private final PendingTransactionsStore store;
    private final Executor executor;
    private final InstantSource time;
    private final Duration retryBackoff;

    @Nullable
    private SavedPendingTransactions pending;

    /**
     * @param store where the transactions were saved
     * @param executor runs the resubmission off the calling thread
     * @param time the time source for the retry window
     * @param retryBackoff pause between retries of one transaction
     */
    public PendingTransactionsRestorer(
            @NonNull final PendingTransactionsStore store,
            @NonNull final Executor executor,
            @NonNull final InstantSource time,
            @NonNull final Duration retryBackoff) {
        this.store = requireNonNull(store);
        this.executor = requireNonNull(executor);
        this.time = requireNonNull(time);
        this.retryBackoff = requireNonNull(retryBackoff);
    }

    /**
     * Loads the saved transactions if the node restarted from the state of their freeze round, keeps the file if it
     * restarted from an earlier state (replay reaches the freeze round again), and deletes it otherwise.
     *
     * @param trigger why the state was initialized
     * @param stateRound the round of the initialized state
     */
    public synchronized void onStateInitialized(@NonNull final InitTrigger trigger, final long stateRound) {
        requireNonNull(trigger);
        pending = null;
        if (trigger == InitTrigger.RESTART) {
            final var saved = store.load().orElse(null);
            if (saved != null && saved.freezeRound() == stateRound) {
                pending = saved;
                logger.info(
                        "Loaded {} pending user transactions saved at freeze round {}",
                        saved.transactions().size(),
                        saved.freezeRound());
                return;
            }
            if (saved != null && saved.freezeRound() > stateRound) {
                // Replay will reach the freeze round again; keep the file for the restart after it
                logger.info(
                        "Keeping {} pending user transactions saved at freeze round {}; started from earlier round {}",
                        saved.transactions().size(),
                        saved.freezeRound(),
                        stateRound);
                return;
            }
            if (saved != null) {
                logger.info(
                        "Discarding {} pending user transactions saved at freeze round {}; started from round {}",
                        saved.transactions().size(),
                        saved.freezeRound(),
                        stateRound);
            }
        }
        store.delete();
    }

    /**
     * Resubmits the loaded transactions on the executor, at most once. The saved file is deleted first, so a crash
     * during the restore never resubmits a transaction twice.
     *
     * @param submitter submits one serialized signed transaction and returns its precheck code
     * @param retryWindow how long to keep retrying transient rejections
     */
    public synchronized void restoreAsync(
            @NonNull final Function<Bytes, ResponseCodeEnum> submitter, @NonNull final Duration retryWindow) {
        requireNonNull(submitter);
        requireNonNull(retryWindow);
        if (pending == null) {
            return;
        }
        final var toRestore = pending;
        pending = null;
        if (!store.delete()) {
            logger.warn(
                    "Not restoring {} pending user transactions: unable to delete the saved file",
                    toRestore.transactions().size());
            return;
        }
        executor.execute(() -> restore(toRestore, submitter, retryWindow));
    }

    private void restore(
            @NonNull final SavedPendingTransactions saved,
            @NonNull final Function<Bytes, ResponseCodeEnum> submitter,
            @NonNull final Duration retryWindow) {
        final Instant deadline = time.instant().plus(retryWindow);
        final Map<ResponseCodeEnum, Integer> dropped = new EnumMap<>(ResponseCodeEnum.class);
        int restored = 0;
        for (final var transaction : saved.transactions()) {
            var code = submit(submitter, transaction);
            while (RETRYABLE.contains(code) && time.instant().isBefore(deadline) && pause()) {
                code = submit(submitter, transaction);
            }
            if (code == OK) {
                restored++;
            } else {
                dropped.merge(code, 1, Integer::sum);
            }
        }
        logger.info(
                "Restored {} of {} pending user transactions (dropped: {})",
                restored,
                saved.transactions().size(),
                dropped);
    }

    private static ResponseCodeEnum submit(
            @NonNull final Function<Bytes, ResponseCodeEnum> submitter, @NonNull final Bytes transaction) {
        try {
            return submitter.apply(transaction);
        } catch (final RuntimeException e) {
            logger.warn("Unable to resubmit a pending user transaction", e);
            return FAIL_INVALID;
        }
    }

    private boolean pause() {
        try {
            Thread.sleep(retryBackoff);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
