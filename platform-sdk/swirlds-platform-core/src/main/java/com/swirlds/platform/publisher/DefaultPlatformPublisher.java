// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.publisher;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.platform.builder.ApplicationCallbacks;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * This component is responsible for publishing internal platform data to external subscribers. By default this is not
 * enabled, and will only publish data if handler methods are registered with the platform at startup time.
 */
public class DefaultPlatformPublisher implements PlatformPublisher {

    private static final Logger logger = LogManager.getLogger(DefaultPlatformPublisher.class);

    private final Consumer<PlatformEvent> preconsensusEventConsumer;
    private boolean preconsensusEventConsumerErrorLogged = false;

    private final Consumer<ConsensusSnapshot> snapshotOverrideConsumer;
    private boolean snapshotOverrideConsumerErrorLogged = false;

    private final Consumer<PlatformEvent> staleEventConsumer;
    private boolean staleEventConsumerErrorLogged = false;

    /**
     * Constructor.
     *
     * @param applicationCallbacks the application callbacks
     */
    public DefaultPlatformPublisher(@NonNull final ApplicationCallbacks applicationCallbacks) {
        this.preconsensusEventConsumer = applicationCallbacks.preconsensusEventConsumer();
        this.snapshotOverrideConsumer = applicationCallbacks.snapshotOverrideConsumer();
        this.staleEventConsumer = applicationCallbacks.staleEventConsumer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishPreconsensusEvent(@NonNull final PlatformEvent event) {
        if (preconsensusEventConsumer == null) {
            if (!preconsensusEventConsumerErrorLogged) {
                // One log is sufficient to alert test validators, no need generate spam beyond the first log.
                logger.error(EXCEPTION.getMarker(), "No preconsensus event consumer is registered");
                preconsensusEventConsumerErrorLogged = true;
            }
            return;
        }
        preconsensusEventConsumer.accept(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishSnapshotOverride(@NonNull final ConsensusSnapshot snapshot) {
        if (snapshotOverrideConsumer == null) {
            if (!snapshotOverrideConsumerErrorLogged) {
                // One log is sufficient to alert test validators, no need generate spam beyond the first log.
                logger.error(EXCEPTION.getMarker(), "No snapshot override consumer is registered");
                snapshotOverrideConsumerErrorLogged = true;
            }
            return;
        }
        snapshotOverrideConsumer.accept(snapshot);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishStaleEvent(@NonNull final PlatformEvent event) {
        if (staleEventConsumer == null) {
            if (!staleEventConsumerErrorLogged) {
                // One log is sufficient to alert test validators, no need generate spam beyond the first log.
                logger.error(EXCEPTION.getMarker(), "No stale event consumer is registered");
                staleEventConsumerErrorLogged = true;
            }
            return;
        }
        staleEventConsumer.accept(event);
    }
}
