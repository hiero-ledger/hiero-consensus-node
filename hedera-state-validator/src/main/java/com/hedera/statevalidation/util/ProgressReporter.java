// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static com.hedera.statevalidation.gcp.GcpPathHelper.CONSOLE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Thread-safe progress reporter that logs percentage milestones to stdout via the
 * {@code CONSOLE} log4j marker. Reports at every 10% boundary: {@code 10%...20%...100%}.
 *
 * <p>Typical usage:
 * <pre>{@code
 *   ProgressReporter progress = new ProgressReporter("Pipeline", totalBytes);
 *   // from any thread:
 *   progress.advance(bytesProcessed);
 * }</pre>
 *
 * <p>When the total is not known upfront (e.g. unbounded stream), use {@link #advanceUnbounded(long)}
 * to periodically log the current count without percentage.
 */
public class ProgressReporter {

    private static final Logger log = LogManager.getLogger(ProgressReporter.class);

    private final String label;
    private final long total;
    private final AtomicLong completed = new AtomicLong();

    /**
     * Interval for unbounded progress reporting: log every N units.
     */
    private static final long DEFAULT_UNBOUNDED_LOG_INTERVAL = 1000;

    private final long unboundedLogInterval;

    /**
     * Creates a bounded progress reporter.
     *
     * @param label the label printed before each percentage (e.g. "Pipeline", "Block stream recovery")
     * @param total the total amount of work; must be positive
     */
    public ProgressReporter(@NonNull final String label, final long total) {
        this(label, total, DEFAULT_UNBOUNDED_LOG_INTERVAL);
    }

    /**
     * Creates a bounded progress reporter.
     *
     * @param label the label printed before each percentage (e.g. "Pipeline", "Block stream recovery")
     * @param total the total amount of work; must be positive
     * @param unboundedLogInterval the interval for unbounded progress reporting
     */
    public ProgressReporter(String label, long total, long unboundedLogInterval) {
        this.label = label;
        this.total = total;
        this.unboundedLogInterval = unboundedLogInterval;
    }

    /**
     * Advances progress by {@code delta} units. If a new 10% boundary is crossed,
     * logs the milestone to stdout.
     *
     * @param delta the amount of work completed (must be non-negative)
     */
    public void advance(final long delta) {
        final long current = completed.addAndGet(delta);
        final long prev = current - delta;
        final int prevDecile = (int) (prev * 10 / total);
        final int currentDecile = (int) Math.min(current * 10 / total, 10);
        for (int d = prevDecile + 1; d <= currentDecile; d++) {
            log.info(CONSOLE, "{}: {}%", label, d * 10);
        }
    }

    /**
     * Advances an unbounded counter (total unknown). Logs the current count
     * every {@link #unboundedLogInterval} units.
     *
     * @param delta the amount of work completed
     */
    public void advanceUnbounded(final long delta) {
        final long current = completed.addAndGet(delta);
        final long prev = current - delta;

        if (current / unboundedLogInterval > prev / unboundedLogInterval) {
            log.info(CONSOLE, "{}: {} processed", label, current);
        }
    }
}
