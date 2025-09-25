// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.instrumented;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.eventbus.EventBus;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.impl.DefaultEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

/**
 * An {@link EventCreatorModule} that wraps the default implementation and adds instrumentation.
 */
public class InstrumentedEventCreator implements EventCreatorModule {

    private static final Logger log = LogManager.getLogger();

    /** Regular implementation that can be used to trigger standard behavior. */
    private final DefaultEventCreator delegate;

    /** The event bus to listen to commands and post events for other components. */
    private EventBus eventBus;

    /**
     * Constructs an instance of this class.
     */
    public InstrumentedEventCreator() {
        delegate = new DefaultEventCreator();

        log.info("InstrumentedEventCreator created");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SecureRandom random,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final EventTransactionSupplier transactionSupplier,
            @NonNull final SignatureTransactionCheck signatureTransactionCheck) {
        delegate.initialize(
                configuration,
                metrics,
                time,
                random,
                keysAndCerts,
                roster,
                selfId,
                transactionSupplier,
                signatureTransactionCheck);

        this.eventBus = EventBus.getInstance(selfId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent maybeCreateEvent() {
        return delegate.maybeCreateEvent();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerEvent(@NonNull final PlatformEvent event) {
        delegate.registerEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        delegate.setEventWindow(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        delegate.updatePlatformStatus(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        delegate.reportUnhealthyDuration(duration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportSyncRoundLag(@NonNull final Double lag) {
        delegate.reportSyncRoundLag(lag);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        delegate.clear();
    }
}
