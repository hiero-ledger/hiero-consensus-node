// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.management.StateManagementModule;
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
     * @param builder                   builds platform components that need to be bound to wires
     * @param eventWindowManager        the event window manager to bind
     * @param notifier                  the notifier to bind
     */
    public void bind(
            @NonNull final PlatformComponentBuilder builder,
            @NonNull final EventWindowManager eventWindowManager,
            @NonNull final AppNotifier notifier) {

        eventWindowManagerWiring.bind(eventWindowManager);
        consensusEventStreamWiring.bind(builder::buildConsensusEventStream);
        notifierWiring.bind(notifier);
        stateGarbageCollectorWiring.bind(builder::buildStateGarbageCollector);
        platformMonitorWiring.bind(builder::buildPlatformMonitor);
        signedStateSentinelWiring.bind(builder::buildSignedStateSentinel);
    }

    /**
     * Creates a new instance of PlatformComponents.
     *
     * @param platformContext      the platform context
     * @param model                the wiring model
     */
    public static PlatformComponents create(
            @NonNull final PlatformContext platformContext,
            @NonNull final WiringModel model,
            @NonNull final EventCreatorModule eventCreatorModule,
            @NonNull final EventIntakeModule eventIntakeModule,
            @NonNull final PcesModule pcesModule,
            @NonNull final HashgraphModule hashgraphModule,
            @NonNull final GossipModule gossipModule,
            @NonNull final IssDetectionModule issDetectionModule,
            @NonNull final TransactionHandlingModule transactionHandlingModule,
            @NonNull final StateManagementModule stateManagementModule) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(model);

        final PlatformSchedulersConfig config =
                platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);
        final EventStreamWiringConfig eventStreamConfig =
                platformContext.getConfiguration().getConfigData(EventStreamWiringConfig.class);

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
