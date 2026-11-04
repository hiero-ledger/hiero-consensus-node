// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.throttle;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;

/**
 * Remembers which exception stack traces have already been reported, so that a full stack trace is logged only the
 * first time a particular one is seen.
 * <p>
 * Two exceptions are considered to have the same stack trace if the whole cause chain matches: the exception types
 * and every stack frame (class, method, line number). Exception messages are intentionally ignored, since they
 * usually contain things like addresses, ports and timings, which would make every occurrence look unique.
 * <p>
 * This class is thread safe. It is not designed for high throughput, it is expected to be called for rare events.
 */
public final class StackTraceDeduplicator {
    /** Cause chains deeper than this are ignored beyond this depth; also protects against cyclic causes */
    private static final int MAX_CAUSE_DEPTH = 10;

    /** Default maximum number of distinct signatures remembered */
    private static final int DEFAULT_MAX_TRACKED = 100;

    private final int maxTracked;
    private final Set<String> seen = new HashSet<>();

    /**
     * Create a deduplicator remembering up to {@value #DEFAULT_MAX_TRACKED} distinct stack traces.
     */
    public StackTraceDeduplicator() {
        this(DEFAULT_MAX_TRACKED);
    }

    /**
     * Create a deduplicator.
     *
     * @param maxTracked maximum number of distinct stack traces to remember; once exceeded, everything is forgotten
     *                   and tracking starts from scratch (worst case, a few traces are reported again)
     */
    public StackTraceDeduplicator(final int maxTracked) {
        if (maxTracked <= 0) {
            throw new IllegalArgumentException("maxTracked must be positive");
        }
        this.maxTracked = maxTracked;
    }

    /**
     * Check if the stack trace of the provided throwable (including its causes) is new. If it is, it is remembered, so
     * the next call with the same stack trace will return false.
     *
     * @param t the throwable to check
     * @return true if this stack trace has not been seen before, false if it was already reported
     */
    public synchronized boolean isNew(@NonNull final Throwable t) {
        final String signature = signature(t);
        if (seen.contains(signature)) {
            return false;
        }
        if (seen.size() >= maxTracked) {
            // safety net against unbounded growth
            seen.clear();
        }
        seen.add(signature);
        return true;
    }

    /**
     * Build a string identifying the stack trace of the throwable and its causes. Messages are not included.
     *
     * @param t the throwable
     * @return the signature of the stack trace
     */
    static String signature(@NonNull final Throwable t) {
        final StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            sb.append(current.getClass().getName()).append('\n');
            for (final StackTraceElement frame : current.getStackTrace()) {
                sb.append(frame.getClassName())
                        .append('.')
                        .append(frame.getMethodName())
                        .append(':')
                        .append(frame.getLineNumber())
                        .append('\n');
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return sb.toString();
    }
}
