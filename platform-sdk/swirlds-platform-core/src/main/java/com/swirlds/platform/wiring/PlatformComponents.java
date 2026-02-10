// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionHandlerDataCounter;
import com.swirlds.platform.eventhandling.TransactionHandlerResult;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.impl.gossip.GossipWiring;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateGarbageCollector;

/**
 * Encapsulates wiring for {@link SwirldsPlatform}.
 */
public record PlatformComponents(
        WiringModel model,
        EventCreatorModule eventCreatorModule,
        EventIntakeModule eventIntakeModule,
        PcesModule pcesModule,
        HashgraphModule hashgraphModule,
        ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                applicationTransactionPrehandlerWiring,
        ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring,
        ComponentWiring<StateSnapshotManager, StateSavingResult> stateSnapshotManagerWiring,
        ComponentWiring<StateSigner, StateSignatureTransaction> stateSignerWiring,
        ComponentWiring<TransactionHandler, TransactionHandlerResult> transactionHandlerWiring,
        ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring,
        RunningEventHashOverrideWiring runningEventHashOverrideWiring,
        ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring,
        GossipWiring gossipWiring,
        ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring,
        ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring,
        ComponentWiring<IssHandler, Void> issHandlerWiring,
        ComponentWiring<HashLogger, Void> hashLoggerWiring,
        ComponentWiring<SignedStateNexus, Void> latestImmutableStateNexusWiring,
        ComponentWiring<LatestCompleteStateNexus, Void> latestCompleteStateNexusWiring,
        ComponentWiring<SavedStateController, StateWithHashComplexity> savedStateControllerWiring,
        ComponentWiring<AppNotifier, Void> notifierWiring,
        ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring,
        ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring,
        ComponentWiring<PlatformMonitor, PlatformStatus> platformMonitorWiring,
        ComponentWiring<BranchDetector, PlatformEvent> branchDetectorWiring,
        ComponentWiring<BranchReporter, Void> branchReporterWiring) {

    /**
     * Bind components to the wiring.
     *
     * @param builder                   builds platform components that need to be bound to wires
     * @param stateSignatureCollector   the signed state manager to bind
     * @param eventWindowManager        the event window manager to bind
     * @param latestImmutableStateNexus the latest immutable state nexus to bind
     * @param latestCompleteStateNexus  the latest complete state nexus to bind
     * @param savedStateController      the saved state controller to bind
     * @param notifier                  the notifier to bind
     */
    public void bind(
            @NonNull final PlatformComponentBuilder builder,
            @NonNull final StateSignatureCollector stateSignatureCollector,
            @NonNull final EventWindowManager eventWindowManager,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final SavedStateController savedStateController,
            @NonNull final AppNotifier notifier) {

        stateSnapshotManagerWiring.bind(builder::buildStateSnapshotManager);
        stateSignerWiring.bind(builder::buildStateSigner);
        stateSignatureCollectorWiring.bind(stateSignatureCollector);
        eventWindowManagerWiring.bind(eventWindowManager);
        applicationTransactionPrehandlerWiring.bind(builder::buildTransactionPrehandler);
        transactionHandlerWiring.bind(builder::buildTransactionHandler);
        consensusEventStreamWiring.bind(builder::buildConsensusEventStream);
        issDetectorWiring.bind(builder::buildIssDetector);
        issHandlerWiring.bind(builder::buildIssHandler);
        hashLoggerWiring.bind(builder::buildHashLogger);
        latestImmutableStateNexusWiring.bind(latestImmutableStateNexus);
        latestCompleteStateNexusWiring.bind(latestCompleteStateNexus);
        savedStateControllerWiring.bind(savedStateController);
        stateHasherWiring.bind(builder::buildStateHasher);
        notifierWiring.bind(notifier);
        stateGarbageCollectorWiring.bind(builder::buildStateGarbageCollector);
        platformMonitorWiring.bind(builder::buildPlatformMonitor);
        signedStateSentinelWiring.bind(builder::buildSignedStateSentinel);
        gossipWiring.bind(builder.buildGossip());
        branchDetectorWiring.bind(builder::buildBranchDetector);
        branchReporterWiring.bind(builder::buildBranchReporter);
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
            @NonNull final HashgraphModule hashgraphModule) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(model);

        final PlatformSchedulersConfig config =
                platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);

        return new PlatformComponents(
                model,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                new ComponentWiring<>(model, TransactionPrehandler.class, config.applicationTransactionPrehandler()),
                new ComponentWiring<>(model, StateSignatureCollector.class, config.stateSignatureCollector()),
                new ComponentWiring<>(model, StateSnapshotManager.class, config.stateSnapshotManager()),
                new ComponentWiring<>(model, StateSigner.class, config.stateSigner()),
                new ComponentWiring<>(
                        model,
                        TransactionHandler.class,
                        config.transactionHandler(),
                        TransactionHandlerDataCounter.create(config.transactionHandler())),
                new ComponentWiring<>(model, ConsensusEventStream.class, config.consensusEventStream()),
                RunningEventHashOverrideWiring.create(model),
                new ComponentWiring<>(
                        model,
                        StateHasher.class,
                        config.stateHasher(),
                        data -> data instanceof final StateWithHashComplexity swhc ? swhc.hashComplexity() : 1),
                new GossipWiring(platformContext.getConfiguration(), model),
                new ComponentWiring<>(model, EventWindowManager.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, IssDetector.class, config.issDetector()),
                new ComponentWiring<>(model, IssHandler.class, config.issHandler()),
                new ComponentWiring<>(model, HashLogger.class, config.hashLogger()),
                new ComponentWiring<>(model, SignedStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, LatestCompleteStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, SavedStateController.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION),
                new ComponentWiring<>(model, StateGarbageCollector.class, config.stateGarbageCollector()),
                new ComponentWiring<>(model, SignedStateSentinel.class, config.signedStateSentinel()),
                new ComponentWiring<>(model, PlatformMonitor.class, config.platformMonitor()),
                new ComponentWiring<>(model, BranchDetector.class, config.branchDetector()),
                new ComponentWiring<>(model, BranchReporter.class, config.branchReporter()));
    }
}
