// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static org.hiero.consensus.platformstate.PlatformStateUtils.isInFreezePeriod;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.system.DefaultPlatformMonitor;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.DefaultConsensusEventStream;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateManagementModule;
import org.hiero.consensus.state.signed.DefaultStateGarbageCollector;
import org.hiero.consensus.state.signed.StateGarbageCollector;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

/**
 * Encapsulates wiring for {@link SwirldsPlatform}.
 */
public record PlatformComponents(
        WiringModel model,
        EventCreatorModule eventCreatorModule,
        EventIntakeModule eventIntakeModule,
        PcesModule pcesModule,
        HashgraphModule hashgraphModule,
        GossipModule gossipModule,
        IssDetectionModule issDetectionModule,
        TransactionHandlingModule transactionHandlingModule,
        StateManagementModule stateManagementModule,
        ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring,
        RunningEventHashOverrideWiring runningEventHashOverrideWiring,
        ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring,
        ComponentWiring<AppNotifier, Void> notifierWiring,
        ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring,
        ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring,
        ComponentWiring<PlatformMonitor, PlatformStatus> platformMonitorWiring) {

    /**
     * Bind components to the wiring.
     *
     * @param inputs                   inputs provided to the consensus layer from the execution layer
     * @param eventWindowManager        the event window manager to bind
     * @param notifier                  the notifier to bind
     */
    public void bind(
            @NonNull final ConsensusLayerInputs inputs,
            @NonNull final EventWindowManager eventWindowManager,
            @NonNull final AppNotifier notifier) {

        eventWindowManagerWiring.bind(eventWindowManager);
        consensusEventStreamWiring.bind(createConsensusEventStream(inputs));
        notifierWiring.bind(notifier);
        stateGarbageCollectorWiring.bind(createStateGarbageCollector(inputs));
        platformMonitorWiring.bind(createPlatformMonitor(inputs));
    }

    @NonNull
    private PlatformMonitor createPlatformMonitor(@NonNull final ConsensusLayerInputs inputs) {
        return new DefaultPlatformMonitor(inputs.configuration(), inputs.metrics(), inputs.time(), inputs.selfId());
    }

    @NonNull
    private StateGarbageCollector createStateGarbageCollector(@NonNull final ConsensusLayerInputs inputs) {
        return new DefaultStateGarbageCollector(inputs.metrics());
    }

    /**
     * Build the consensus event stream if it has not yet been built.
     *
     * @return the consensus event stream
     */
    @NonNull
    private ConsensusEventStream createConsensusEventStream(@NonNull final ConsensusLayerInputs inputs) {
            final Predicate<CesEvent> isLastEventInFreezePeriod = (CesEvent event) -> {
                final Instant consensusTimestamp = event.getConsensusTimestamp();
                final VirtualMapState mutableState = inputs.stateLifecycleManager().getMutableState();
                return event.isLastInRoundReceived() && isInFreezePeriod(consensusTimestamp, mutableState);
            };
        return new DefaultConsensusEventStream(
                inputs.time(),
                inputs.configuration(),
                inputs.metrics(),
                inputs.selfId(),
                (byte[] data) -> new PlatformSigner(inputs.keysAndCerts()).sign(data),
                inputs.consensusEventStreamName(),
                isLastEventInFreezePeriod);
    }

    /**
     * Creates a new instance of PlatformComponents.
     */
    public static PlatformComponents create(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final EventCreatorModule eventCreatorModule,
            @NonNull final EventIntakeModule eventIntakeModule,
            @NonNull final PcesModule pcesModule,
            @NonNull final HashgraphModule hashgraphModule,
            @NonNull final GossipModule gossipModule,
            @NonNull final IssDetectionModule issDetectionModule,
            @NonNull final TransactionHandlingModule transactionHandlingModule,
            @NonNull final StateManagementModule stateManagementModule) {

        Objects.requireNonNull(model);

        final PlatformSchedulersConfig config =
                configuration.getConfigData(PlatformSchedulersConfig.class);
        final EventStreamWiringConfig eventStreamConfig =
                configuration.getConfigData(EventStreamWiringConfig.class);

        return new PlatformComponents(
                model,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                gossipModule,
                issDetectionModule,
                transactionHandlingModule,
                stateManagementModule,
                new ComponentWiring<>(model, ConsensusEventStream.class, eventStreamConfig.consensusEventStream()),
                RunningEventHashOverrideWiring.create(model),
                new ComponentWiring<>(model, EventWindowManager.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, StateGarbageCollector.class, config.stateGarbageCollector()),
                new ComponentWiring<>(model, SignedStateSentinel.class, config.signedStateSentinel()),
                new ComponentWiring<>(model, PlatformMonitor.class, config.platformMonitor()));
    }
}
