// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.Validator;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;

/**
 * Implementation of the {@link Validator} interface.
 */
public class ValidatorImpl implements Validator {

    private static final Logger log = LogManager.getLogger(ValidatorImpl.class);

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertLogs(@NonNull final LogFilter... configs) {
        Objects.requireNonNull(configs, "configs cannot be null");
        // Take a snapshot of current logs to ensure consistent view and avoid race conditions during filtering
        final List<StructuredLog> logs = new ArrayList<>(InMemoryAppender.getLogs());
        final List<StructuredLog> filteredLogs = logs.stream()
                .filter(log -> Arrays.stream(configs).allMatch(filter -> filter.filter(log)))
                .toList();

        if (!filteredLogs.isEmpty()) {
            final StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("\n****************\n");
            errorMsg.append(" ->  Log errors found:\n");
            errorMsg.append("****************\n");
            filteredLogs.forEach(log -> errorMsg.append(log.toString()));
            errorMsg.append("****************\n");

            fail(errorMsg.toString());
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertStdOut() {
        log.warn("stdout validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator eventStream(@NonNull final EventStreamConfig... configs) {
        log.warn("event stream validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator reconnectEventStream(@NonNull final Node node) {
        log.warn("reconnect event stream validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator validateRemaining(@NonNull final Profile profile) {
        log.warn("remaining validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator consensusRatio(@NonNull final RatioConfig... configs) {
        log.warn("consensus ratio validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator staleRatio(@NonNull final RatioConfig... configs) {
        log.warn("stale ratio validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertPlatformStatus(@NonNull PlatformStatusConfig... configs) {
        log.warn("platform status validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertMetrics(@NonNull MetricsConfig... configs) {
        log.warn("metrics validation is not implemented yet.");
        return this;
    }
}
