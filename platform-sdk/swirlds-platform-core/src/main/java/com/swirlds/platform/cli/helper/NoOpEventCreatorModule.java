// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli.helper;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.time.Duration;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig;
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
     * @param configuration the configuration
     * @param model         the wiring model
     */
    public NoOpEventCreatorModule(@NonNull final Configuration configuration, @NonNull final WiringModel model) {
        final EventCreationWiringConfig wiringConfig = configuration.getConfigData(EventCreationWiringConfig.class);
        componentWiring = new ComponentWiring<>(model, EventCreatorModule.class, wiringConfig.eventCreationManager());
        componentWiring.bind(this);
    }

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

    @NonNull
    @Override
    public OutputWire<PlatformEvent> createdEventOutputWire() {
        return componentWiring.getOutputWire();
    }

    @NonNull
    @Override
    public InputWire<PlatformEvent> orderedEventInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::orderedEventInputWire);
    }

    @NonNull
    @Override
    public InputWire<EventWindow> eventWindowInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::eventWindowInputWire);
    }

    @NonNull
    @Override
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::platformStatusInputWire);
    }

    @NonNull
    @Override
    public InputWire<Duration> healthStatusInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::healthStatusInputWire);
    }

    @NonNull
    @Override
    public InputWire<SyncProgress> syncProgressInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::syncProgressInputWire);
    }

    @NonNull
    @Override
    public InputWire<QuiescenceCommand> quiescenceCommandInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::quiescenceCommandInputWire);
    }

    @Override
    public void destroy() {}

    @Override
    public void startSquelching() {}

    @Override
    public void flush() {}

    @Override
    public void stopSquelching() {}

    @NonNull
    @Override
    public InputWire<Object> clearCreationMangerInputWire() {
        return componentWiring.getInputWire(EventCreatorModule::clearCreationMangerInputWire);
    }
}
