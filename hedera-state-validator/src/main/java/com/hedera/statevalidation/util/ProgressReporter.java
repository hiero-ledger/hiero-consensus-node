// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static com.hedera.statevalidation.gcp.GcpPathHelper.CONSOLE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger lastReportedDecile = new AtomicInteger(0);

    /**
     * Interval for unbounded progress reporting: log every N units.
     */
    private static final long UNBOUNDED_LOG_INTERVAL = 1000;

    /**
     * Creates a bounded progress reporter.
     *
     * @param label the label printed before each percentage (e.g. "Pipeline", "Block stream recovery")
     * @param total the total amount of work; must be positive
     */
    public ProgressReporter(@NonNull final String label, final long total) {
        this.label = label;
        this.total = total;
    }

    /**
     * Advances progress by {@code delta} units. If a new 10% boundary is crossed,
     * logs the milestone to stdout.
     *
     * @param delta the amount of work completed (must be non-negative)
     */
    public void advance(final long delta) {
        final long current = completed.addAndGet(delta);
        final int decile = (int) Math.min(current * 10 / total, 10);
        int prev = lastReportedDecile.get();
        // Report every 10% decile that was crossed since the last report
        while (decile > prev) {
            if (lastReportedDecile.compareAndSet(prev, decile)) {
                for (int d = prev + 1; d <= decile; d++) {
                    log.info(CONSOLE, "{}: {}%", label, d * 10);
                }
                break;
            }
            prev = lastReportedDecile.get();
        }
    }

    /**
     * Advances an unbounded counter (total unknown). Logs the current count
     * every {@value UNBOUNDED_LOG_INTERVAL} units.
     *
     * @param delta the amount of work completed
     */
    public void advanceUnbounded(final long delta) {
        final long prev = completed.get();
        final long current = completed.addAndGet(delta);
        if (current / UNBOUNDED_LOG_INTERVAL > prev / UNBOUNDED_LOG_INTERVAL) {
            log.info(CONSOLE, "{}: {} processed", label, current);
        }
    }
}