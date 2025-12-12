// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli.helper;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.time.Duration;
import org.hiero.consensus.event.creator.EventCreatorModule;
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
 * A no-operation implementation of the EventCreatorModule for the {@link com.swirlds.platform.cli.DiagramCommand}.
 */
public class NoOpEventCreatorModule implements EventCreatorModule {

    private final ComponentWiring<EventCreatorModule, PlatformEvent> componentWiring;

    /**
     * Constructs a NoOpEventCreatorModule.
     *
     * @param model         the wiring model
     */
    public NoOpEventCreatorModule(@NonNull final WiringModel model) {
        componentWiring =
                new ComponentWiring<>(model, EventCreatorModule.class, TaskSchedulerConfiguration.DIRECT_CONFIGURATION);
        componentWiring.bind(this);
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
            @NonNull final EventTransactionSupplier transactionSupplier,
            @NonNull final SignatureTransactionCheck signatureTransactionCheck) {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> createdEventOutputWire() {
        return componentWiring.getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> orderedEventInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::orderedEventInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::eventWindowInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::platformStatusInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Duration> healthStatusInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::healthStatusInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<SyncProgress> syncProgressInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::syncProgressInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<QuiescenceCommand> quiescenceCommandInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::quiescenceCommandInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelching() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelching() {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> clearCreationMangerInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::clearCreationMangerInputWire);
    }
}
