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
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

/**
 * Default implementation of the {@link EventCreatorModule}.
 */
public class DefaultEventCreatorModule implements EventCreatorModule {

    @Nullable
    private ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;

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
            @NonNull final EventTransactionSupplier transactionSupplier,
            @NonNull final SignatureTransactionCheck signatureTransactionCheck) {
        //noinspection VariableNotUsedInsideIf
        if (eventCreationManagerWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        // Read module configuration
        final EventCreationConfig eventCreationConfig = configuration.getConfigData(EventCreationConfig.class);
        final EventCreationWiringConfig wiringConfig = configuration.getConfigData(EventCreationWiringConfig.class);

        // Set up component wiring
        this.eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreationManager.class, wiringConfig.eventCreationManager());

        // Set up heartbeat wire
        model.buildHeartbeatWire(eventCreationConfig.frequency())
                .solderTo(
                        eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent, "heartbeat"),
                        OFFER);

        // Force not soldered wires to be built
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear);
        eventCreationManagerWiring.getInputWire(EventCreationManager::quiescenceCommand);

        // Create and bind components
        final BytesSigner bytesSigner = new PlatformSigner(keysAndCerts);
        final EventCreator eventCreator = new TipsetEventCreator(
                configuration, metrics, time, random, bytesSigner, roster, selfId, transactionSupplier);
        final DefaultEventCreationManager eventCreationManager = new DefaultEventCreationManager(
                configuration, metrics, time, signatureTransactionCheck, eventCreator, roster, selfId);
        eventCreationManagerWiring.bind(eventCreationManager);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> createdEventOutputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized").getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> orderedEventInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::registerEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::setEventWindow, "event window");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::updatePlatformStatus, "PlatformStatus");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Duration> healthStatusInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::reportUnhealthyDuration, "health info");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<SyncProgress> syncProgressInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::reportSyncProgress);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<QuiescenceCommand> quiescenceCommandInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized")
                .getInputWire(EventCreationManager::quiescenceCommand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        throw new UnsupportedOperationException("Shutdown mechanism not implemented yet");
    }

    // *****************************************************************
    // Temporary workaround to allow reuse of the EventCreator component
    // *****************************************************************

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
    public InputWire<Object> clearCreationMangerInputWire() {
        return requireNonNull(eventCreationManagerWiring, "Not initialized").getInputWire(EventCreationManager::clear);
    }
}
