// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.impl.gossip.Gossip;
import org.hiero.consensus.gossip.impl.gossip.GossipWiring;
import org.hiero.consensus.gossip.impl.gossip.SyncGossipModular;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.gossip.impl.reconnect.ProtocolFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Default implementation of {@link GossipModule}.
 */
public final class DefaultGossipModule implements GossipModule {

    @Nullable
    private GossipWiring gossipWiring;

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster currentRoster,
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion appVersion,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {
        if (gossipWiring != null) {
            throw new IllegalStateException("Gossip module has already been initialized");
        }

        // Set up wiring (the gossip module is initialized differently. This should be revisited.
        this.gossipWiring = new GossipWiring(configuration, model);

        // Create and bind components
        final ThreadManager threadManager = AdHocThreadManager.getStaticThreadManager();
        final ProtocolFactory factory =
                ServiceLoader.load(ProtocolFactory.class).findFirst().orElseThrow();
        final Protocol reconnectProtocol = factory.createProtocol(
                configuration,
                metrics,
                time,
                threadManager,
                latestCompleteState,
                reservedSignedStateResultPromise,
                fallenBehindMonitor,
                stateLifecycleManager);
        final Gossip gossip = new SyncGossipModular(
                configuration,
                metrics,
                time,
                threadManager,
                keysAndCerts,
                currentRoster,
                selfId,
                appVersion,
                intakeEventCounter,
                fallenBehindMonitor,
                reconnectProtocol);
        gossipWiring.bind(gossip);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> receivedEventOutputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getEventOutput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<SyncProgress> syncProgressOutputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getSyncProgressOutput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> eventToGossipInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getEventInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getEventWindowInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getPlatformStatusInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Duration> healthStatusInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getSystemHealthInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<NoInput> startInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getStartInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<NoInput> stopInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getStopInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<NoInput> clearInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").getClearInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<NoInput> pauseInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").pauseInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<NoInput> resumeInputWire() {
        return requireNonNull(gossipWiring, "Not initialized").resumeInput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(gossipWiring, "Not initialized").flush();
    }
}
