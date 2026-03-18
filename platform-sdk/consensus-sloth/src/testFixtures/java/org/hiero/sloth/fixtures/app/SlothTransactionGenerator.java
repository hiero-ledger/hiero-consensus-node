// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.hiero.sloth.fixtures.SlothTransactionType;
import org.hiero.sloth.fixtures.TransactionFactory;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;

/**
 * Generates {@link SlothTransaction}s at a configurable rate and submits them directly to a
 * {@link TransactionPoolNexus}.
 *
 * <p>Generation runs on a dedicated background thread managed internally by this class.
 * Callers use {@link #startGenerating} and {@link #stopGenerating} to control the lifecycle.
 */
public class SlothTransactionGenerator {

    private static final Logger log = LogManager.getLogger(SlothTransactionGenerator.class);

    /** Pool that generated transactions are submitted to. */
    private final TransactionPoolNexus transactionPool;

    /** Source of time used to timestamp {@link SlothTransactionType#BENCHMARK} transactions. */
    private final Time time;

    /** Single-threaded scheduler driving the periodic generation task. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "sloth-tx-generator");
        t.setDaemon(true);
        return t;
    });

    /** Handle of the currently running generation task, or {@code null} when idle. */
    private volatile ScheduledFuture<?> generationTask;

    /** Monotonically increasing nonce so every generated transaction has a unique identity. */
    private final AtomicLong nonceGenerator = new AtomicLong(0);

    /** Tracks how many transactions have been successfully submitted since the last {@link #startGenerating} call. */
    private final AtomicLong generatedCount = new AtomicLong(0);

    /**
     * Creates a new generator.
     *
     * @param transactionPool the pool to submit generated transactions to
     * @param time            source of time for benchmark transaction timestamps
     */
    public SlothTransactionGenerator(@NonNull final TransactionPoolNexus transactionPool, @NonNull final Time time) {
        this.transactionPool = requireNonNull(transactionPool);
        this.time = requireNonNull(time);
    }

    /**
     * Starts generating transactions of the specified type at the given rate.
     *
     * <p>If generation is already running it is stopped and restarted with the new parameters.
     * The generated-count counter is reset to zero on each call.
     *
     * @param tps  the number of transactions to generate per second; must be positive; may be fractional
     * @param type the type of transaction to generate
     * @throws IllegalArgumentException if {@code tps} is not positive
     */
    public void startGenerating(final double tps, @NonNull final SlothTransactionType type) {
        if (tps <= 0.0) {
            throw new IllegalArgumentException("tps must be positive, got: " + tps);
        }
        requireNonNull(type);

        // Cancel any in-flight task before configuring a new one.
        final ScheduledFuture<?> existing = generationTask;
        if (existing != null) {
            existing.cancel(false);
        }
        generatedCount.set(0);

        final long intervalMicros = (long) (1_000_000.0 / tps);
        log.info("Starting transaction generation: {} TPS, type={}, interval={}μs", tps, type, intervalMicros);
        generationTask =
                scheduler.scheduleAtFixedRate(() -> generateAndSubmit(type), 0, intervalMicros, TimeUnit.MICROSECONDS);
    }

    /**
     * Stops transaction generation.
     *
     * <p>This method is idempotent; calling it when generation is not running has no effect.
     *
     * @return the total number of transactions successfully submitted to the pool since the last
     *         call to {@link #startGenerating}
     */
    public long stopGenerating() {
        final ScheduledFuture<?> task = generationTask;
        if (task != null) {
            task.cancel(false);
            generationTask = null;
        }
        // cancel(false) does not interrupt a currently running generateAndSubmit().
        // Submit a fence task to the single-threaded scheduler and wait for it to
        // complete — this guarantees any in-flight execution has finished and
        // generatedCount is up to date before we read it.
        try {
            scheduler.submit(() -> {}).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for in-flight generation to complete", e);
        } catch (final Exception e) {
            log.warn("Error waiting for in-flight generation to complete", e);
        }
        final long count = generatedCount.get();
        log.info("Stopped transaction generation. Total generated: {}", count);
        return count;
    }

    private void generateAndSubmit(@NonNull final SlothTransactionType type) {
        final SlothTransaction tx =
                switch (type) {
                    case EMPTY -> TransactionFactory.createEmptyTransaction(nonceGenerator.incrementAndGet());
                    case BENCHMARK ->
                        TransactionFactory.createBenchmarkTransaction(nonceGenerator.incrementAndGet(), time.now());
                };
        if (transactionPool.submitApplicationTransaction(Bytes.wrap(tx.toByteArray()))) {
            generatedCount.incrementAndGet();
        }
    }
}
