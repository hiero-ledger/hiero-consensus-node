// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import static com.swirlds.component.framework.wires.SolderType.OFFER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.ConsensusEventCreator;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator.HashSigner;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Default implementation of the {@link ConsensusEventCreator}.
 */
public class ConsensusEventCreatorImpl implements ConsensusEventCreator {

    @Nullable
    private ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> getOrderedEventsInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::registerEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> getMaybeCreatedEventOutputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized").getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Duration> getHealthStatusInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::reportUnhealthyDuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformStatus> getPlatformStatusInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::updatePlatformStatus);
    }

    @NonNull
    @Override
    public InputWire<Double> getSyncRoundLagInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::reportSyncRoundLag);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SecureRandom random,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final TransactionSupplier transactionSupplier) {
        if (eventCreationManagerWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        // Set up wiring
        final EventCreationWiringConfig wiringConfig = configuration.getConfigData(EventCreationWiringConfig.class);

        this.eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreationManager.class, wiringConfig.eventCreationManager());

        // Set up heartbeat
        final double eventCreationHeartbeatFrequency =
                configuration.getConfigData(EventCreationConfig.class).creationAttemptRate();
        model.buildHeartbeatWire(eventCreationHeartbeatFrequency)
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent), OFFER);

        // Force not soldered wires to be built
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear);

        // Create and bind components
        eventCreationManagerWiring.bind(() -> createEventCreationManager(
                configuration, metrics, time, random, keysAndCerts, roster, selfId, transactionSupplier));
    }

    private static EventCreationManager createEventCreationManager(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SecureRandom random,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final TransactionSupplier transactionSupplier) {
        final HashSigner hashSigner = data -> new PlatformSigner(keysAndCerts).sign(data);
        final EventCreator eventCreator = new TipsetEventCreator(
                configuration, metrics, time, random, hashSigner, roster, selfId, transactionSupplier);

        return new DefaultEventCreationManager(configuration, metrics, time, transactionSupplier, eventCreator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusEventCreator destroy() {
        throw new UnsupportedOperationException("Shutdown mechanism not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> getEventWindowInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::setEventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelching() {
        requireNonNull(eventCreationManagerWiring, "Not initialized").startSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(eventCreationManagerWiring, "Not initialized").flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelching() {
        requireNonNull(eventCreationManagerWiring, "Not initialized").stopSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> getClearEventCreationMangerInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized").getInputWire(EventCreationManager::clear);
    }
}
