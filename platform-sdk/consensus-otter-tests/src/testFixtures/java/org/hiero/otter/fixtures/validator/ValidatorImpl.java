// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.LogErrorConfig;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.Validator;
import org.hiero.otter.fixtures.internal.logging.InMemoryAppender;
import org.hiero.otter.fixtures.internal.logging.StructuredLog;

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
    public Validator assertLogs(@NonNull final LogErrorConfig... configs) {
        Objects.requireNonNull(configs, "configs cannot be null");
        final List<StructuredLog> logs = new ArrayList<>(InMemoryAppender.getLogs());
        final List<LogErrorConfig> sortedConfigs = Stream.of(configs)
                .sorted(Comparator.comparing(LogErrorConfig::getType))
                .toList();
        for (final LogErrorConfig config : sortedConfigs) {
            switch (config.getType()) {
                case MAX_LEVEL -> {
                    final List<StructuredLog> toRemove = logs.stream()
                            .filter(Objects::nonNull)
                            .filter(msg ->
                                    msg.level().intLevel() >= config.getLevel().intLevel())
                            .toList();
                    logs.removeAll(toRemove);
                }
                case IGNORE_MARKER -> {
                    final List<StructuredLog> toRemove = logs.stream()
                            .filter(Objects::nonNull)
                            .filter(log -> config.getIgnoredMarkers().contains(log.marker()))
                            .toList();
                    logs.removeAll(toRemove);
                }
                case IGNORE_NODE -> {
                    final List<StructuredLog> toRemove = logs.stream()
                            .filter(Objects::nonNull)
                            .filter(log -> config.getIgnoreNodes().contains(log.nodeId()))
                            .toList();
                    logs.removeAll(toRemove);
                }
            }
        }
        if (!logs.isEmpty()) {
            final StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("\n****************\n");
            errorMsg.append(" ->  Log errors found:\n");
            errorMsg.append("****************\n");
            logs.forEach(log -> errorMsg.append(log.toString()));
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
