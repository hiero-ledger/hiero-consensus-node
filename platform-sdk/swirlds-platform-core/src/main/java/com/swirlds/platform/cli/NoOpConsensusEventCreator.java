// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.time.Duration;
import org.hiero.consensus.event.creator.ConsensusEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

public class NoOpConsensusEventCreator implements ConsensusEventCreator {

    @NonNull
    @Override
    public InputWire<PlatformEvent> getOrderedEventsInputWire() {
        return null;
    }

    @NonNull
    @Override
    public OutputWire<PlatformEvent> getMaybeCreatedEventOutputWire() {
        return null;
    }

    @NonNull
    @Override
    public InputWire<Duration> getHealthStatusInputWire() {
        return null;
    }

    @NonNull
    @Override
    public InputWire<PlatformStatus> getPlatformStatusInputWire() {
        return null;
    }

    @NonNull
    @Override
    public InputWire<Double> getSyncRoundLagInputWire() {
        return null;
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

    @NonNull
    @Override
    public ConsensusEventCreator destroy() {
        return null;
    }

    @NonNull
    @Override
    public InputWire<EventWindow> getEventWindowInputWire() {
        return null;
    }

    @Override
    public void startSquelching() {}

    @Override
    public void flush() {}

    @Override
    public void stopSquelching() {}

    @NonNull
    @Override
    public InputWire<Object> getClearEventCreationMangerInputWire() {
        return null;
    }
}
