// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BooleanSupplier;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.LogPayloadFinder;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * A {@link LogSubscriber} that searches for a specific payload in log messages.
 * It implements {@link BooleanSupplier} to indicate whether the payload was found,
 * and {@link AutoCloseable} to allow for clean-up when done searching.
 */
public class LogPayloadFinderImpl implements LogPayloadFinder, LogSubscriber {

    private final String payload;
    private volatile boolean found;
    private volatile boolean done;

    /**
     * Constructs a new {@code PayloadFinderImpl} that searches for the specified payload.
     *
     * @param payload the payload to search for in log messages
     */
    public LogPayloadFinderImpl(@NonNull final String payload) {
        this.payload = requireNonNull(payload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SubscriberAction onLogEntry(@NonNull final StructuredLog logEntry) {
        if (logEntry.message().contains(payload)) {
            found = true;
            done = true;
        }
        return done ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
    }

    /**
     * Indicates whether the specified payload was found in the log messages.
     *
     * @return {@code true} if the payload was found, {@code false} otherwise
     */
    public boolean found() {
        return found;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        done = true;
    }
}
