// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.hiero.consensus.event.creator.ConsensusEventCreator;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

public class NoOpConsensusEventCreator implements ConsensusEventCreator {

    private final ComponentWiring<ConsensusEventCreator, PlatformEvent> componentWiring;

    public NoOpConsensusEventCreator(
            @NonNull final Configuration configuration, @NonNull final TraceableWiringModel model) {
        final EventCreationWiringConfig wiringConfig = configuration.getConfigData(EventCreationWiringConfig.class);
        componentWiring =
                new ComponentWiring<>(model, ConsensusEventCreator.class, wiringConfig.eventCreationManager());
        componentWiring.bind(this);
    }

    @Override
    @NonNull
    public InputWire<PlatformEvent> getOrderedEventsInputWire() {
        return componentWiring.getInputWire(ConsensusEventCreator::getOrderedEventsInputWire);
    }

    @Override
    @NonNull
    public OutputWire<PlatformEvent> getMaybeCreatedEventOutputWire() {
        return componentWiring.getOutputWire();
    }

    @Override
    @NonNull
    public InputWire<Duration> getHealthStatusInputWire() {
        return componentWiring.getInputWire(ConsensusEventCreator::getHealthStatusInputWire);
    }

    @Override
    @NonNull
    public InputWire<Instant> getHeartbeatInputWire() {
        return componentWiring.getInputWire(ConsensusEventCreator::getHeartbeatInputWire);
    }

    @Override
    @NonNull
    public InputWire<PlatformStatus> getPlatformStatusInputWire() {
        return componentWiring.getInputWire(ConsensusEventCreator::getPlatformStatusInputWire);
    }

    @Override
    @NonNull
    public InputWire<Double> getSyncRoundLagInputWire() {
        return componentWiring.getInputWire(ConsensusEventCreator::getSyncRoundLagInputWire);
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
            @NonNull final TransactionSupplier transactionSupplier) {}

    @Override
    @NonNull
    public ConsensusEventCreator destroy() {
        return null;
    }

    @Override
    @NonNull
    public InputWire<EventWindow> getEventWindowInputWire() {
        return componentWiring.getInputWire(ConsensusEventCreator::getEventWindowInputWire);
    }

    @Override
    public void startSquelching() {}

    @Override
    public void flush() {}

    @Override
    public void stopSquelching() {}

    @Override
    @NonNull
    public InputWire<Object> getClearEventCreationMangerInputWire() {
        return null;
    }
}
