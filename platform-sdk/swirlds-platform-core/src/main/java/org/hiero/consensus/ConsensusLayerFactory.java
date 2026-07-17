// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createModule;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.util.BootstrapUtils.startJVMPauseDetectorThread;
import static com.swirlds.platform.util.BootstrapUtils.writeSettingsUsed;
import static java.util.Objects.requireNonNullElseGet;
import static org.hiero.base.file.FileUtils.getAbsolutePath;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.platformstate.PlatformStateUtils.isInFreezePeriod;
import static org.hiero.consensus.platformstate.PlatformStateUtils.latestFreezeRoundOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import com.swirlds.platform.reconnect.ReconnectModule;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.platform.wiring.PlatformSchedulersConfig;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.PcesReplayProgress;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.SavedStateController;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.state.nexus.DefaultLatestCompleteStateNexus;
import org.hiero.consensus.state.nexus.LatestCompleteStateNexus;
import org.hiero.consensus.state.nexus.LockFreeStateNexus;
import org.hiero.consensus.state.nexus.SignedStateNexus;
import org.hiero.consensus.state.persistence.DefaultSavedStateController;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.status.StatusActionSubmitter;
import org.hiero.consensus.system.SystemExitUtils;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

/**
 * A factory used to construct the consensus layer.
 */
public class ConsensusLayerFactory {

    private static final Logger logger = LogManager.getLogger();

    private static final UncaughtExceptionHandler DEFAULT_UNCAUGHT_EXCEPTION_HANDLER =
            (t, e) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception on thread {}: {}", t, e);

    /**
     * The path to the settings file (i.e. the file with the optional settings).
     */
    private static final Path DEFAULT_SETTINGS_PATH = getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME);

    private static boolean staticSetupCompleted = false;

    @NonNull
    private final Configuration configuration;

    @NonNull
    private final Metrics metrics;

    @NonNull
    private final Time time;

    @NonNull
    private final RosterHistory rosterHistory;

    @NonNull
    private final KeysAndCerts keysAndCerts;

    @NonNull
    private final NodeId selfId;

    @NonNull
    private final RecycleBin recycleBin;

    @NonNull
    private final FileSystemManager fileSystemManager;

    @NonNull
    private final ExecutionLayer executionLayer;

    @NonNull
    private final ConsensusStateEventHandler consensusStateEventHandler;

    @NonNull
    private final ReservedSignedState initialState;

    @NonNull
    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    @NonNull
    private final SemanticVersion version;

    @NonNull
    private final String appName;

    @NonNull
    private final String swirldName;

    private final long transactionOffsetNanos;

    @NonNull
    private final WiringModel wiringModel;

    @NonNull
    private final SecureRandom secureRandom;

    @NonNull
    private final ExecutorFactory executorFactory;

    @Nullable
    private final GossipModule gossipModuleOverride;

    /**
     * Creates a new factory with the inputs provided by the execution layer.
     *
     * @param inputs inputs for the consensus layer
     */
    public ConsensusLayerFactory(@NonNull final ConsensusLayerInputs inputs) {
        configuration = inputs.configuration();
        metrics = inputs.metrics();
        time = inputs.time();
        rosterHistory = inputs.rosterHistory();
        keysAndCerts = inputs.keysAndCerts();
        selfId = inputs.selfId();
        recycleBin = inputs.recycleBin();
        fileSystemManager = inputs.fileSystemManager();
        executionLayer = inputs.executionLayer();
        consensusStateEventHandler = inputs.consensusStateEventHandler();
        initialState = inputs.initialState();
        stateLifecycleManager = inputs.stateLifecycleManager();
        version = inputs.version();
        appName = inputs.appName();
        swirldName = inputs.swirldName();
        transactionOffsetNanos = inputs.transactionOffsetNanos();
        executorFactory = ExecutorFactory.create("platform", null, DEFAULT_UNCAUGHT_EXCEPTION_HANDLER);
        wiringModel = initializeWiringModel(inputs.wiringModel());
        secureRandom = initializeSecureRandom(inputs.secureRandom());
        gossipModuleOverride = inputs.gossipModuleOverride();
    }

    /**
     * The output of the factory.
     */
    public record ConsensusLayerFactoryResult(
            @NonNull PlatformCoordinator platformCoordinator,
            @NonNull ConsensusLayerBuildingBlocks consensusLayerBuildingBlocks) {}

    /**
     * Constructs most of the components and modules required to create the platform.
     */
    @NonNull
    public ConsensusLayerFactoryResult create() {
        final EventCreatorModule eventCreatorModule = createEventCreatorModule();
        final IntakeEventCounter intakeEventCounter = createIntakeEventCounter();
        final EventPipelineTracker eventPipelineTracker = createEventPipelineTracker(eventCreatorModule);
        final EventIntakeModule eventIntakeModule = createEventIntakeModule(intakeEventCounter, eventPipelineTracker);
        final HashgraphModule hashgraphModule = createHashgraphModule(eventPipelineTracker);
        final LatestCompleteStateNexus latestCompleteStateNexus =
                new DefaultLatestCompleteStateNexus(configuration, metrics);
        final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise =
                new BlockingResourceProvider<>();
        final FallenBehindMonitor fallenBehindMonitor =
                new FallenBehindMonitor(rosterHistory.getCurrentRoster(), configuration, selfId);
        final GossipModule gossipModule = createGossipModule(
                intakeEventCounter, latestCompleteStateNexus, reservedSignedStateResultPromise, fallenBehindMonitor);
        final IssDetectionModule issDetectionModule = createIssDetectionModule();

        final AtomicReference<StatusActionSubmitter> statusActionSubmitterReference = new AtomicReference<>();
        final SignedStateNexus latestImmutableStateNexus = createLatestImmutableStateNexus(initialState);
        final TransactionHandlingModule transactionHandlingModule =
                createTransactionHandlingModule(latestImmutableStateNexus, statusActionSubmitterReference);

        final SavedStateController savedStateController = new DefaultSavedStateController(configuration);
        final StateModule stateModule = createStateModule(latestCompleteStateNexus, savedStateController);

        final PcesModule pcesModule = createModule(PcesModule.class, configuration);

        final PlatformSchedulersConfig platformSchedulersConfig =
                configuration.getConfigData(PlatformSchedulersConfig.class);
        final EventStreamWiringConfig eventStreamConfig = configuration.getConfigData(EventStreamWiringConfig.class);
        final ComponentWiring<ConsensusEventStream, Void> eventStreamWiring = new ComponentWiring<>(
                wiringModel, ConsensusEventStream.class, eventStreamConfig.consensusEventStream());
        final RunningEventHashOverrideWiring runningEventHashOverrideWiring =
                RunningEventHashOverrideWiring.create(wiringModel);
        final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring =
                new ComponentWiring<>(wiringModel, EventWindowManager.class, DIRECT_THREADSAFE_CONFIGURATION);
        final ComponentWiring<AppNotifier, Void> notifierWiring =
                new ComponentWiring<>(wiringModel, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION);
        final ComponentWiring<PlatformMonitor, PlatformStatus> platformMonitorWiring =
                new ComponentWiring<>(wiringModel, PlatformMonitor.class, platformSchedulersConfig.platformMonitor());
        final NotificationEngine notificationEngine = NotificationEngine.buildEngine(getStaticThreadManager());
        final PlatformComponents platformComponents = new PlatformComponents(
                wiringModel,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                gossipModule,
                issDetectionModule,
                transactionHandlingModule,
                stateModule,
                eventStreamWiring,
                runningEventHashOverrideWiring,
                eventWindowManagerWiring,
                notifierWiring,
                platformMonitorWiring);
        final PlatformCoordinator platformCoordinator = new PlatformCoordinator(platformComponents);
        initializePcesModule(pcesModule, platformCoordinator, latestImmutableStateNexus, eventPipelineTracker);

        statusActionSubmitterReference.set(platformCoordinator);

        doStaticSetup(configuration);

        return new ConsensusLayerFactoryResult(
                platformCoordinator,
                new ConsensusLayerBuildingBlocks(
                        wiringModel,
                        configuration,
                        eventCreatorModule,
                        eventIntakeModule,
                        pcesModule,
                        hashgraphModule,
                        gossipModule,
                        issDetectionModule,
                        transactionHandlingModule,
                        stateModule,
                        eventStreamWiring,
                        runningEventHashOverrideWiring,
                        eventWindowManagerWiring,
                        notifierWiring,
                        platformMonitorWiring,
                        notificationEngine,
                        savedStateController,
                        platformComponents,
                        reservedSignedStateResultPromise,
                        fallenBehindMonitor,
                        intakeEventCounter));
    }

    @NonNull
    private SignedStateNexus createLatestImmutableStateNexus(@NonNull final ReservedSignedState initialState) {
        final SignedStateNexus latestImmutableStateNexus = new LockFreeStateNexus();
        latestImmutableStateNexus.setState(initialState.get().reserve("set latest immutable to initial state"));
        return latestImmutableStateNexus;
    }

    @NonNull
    public ReconnectModule createReconnectModule(
            @NonNull final Platform platform,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final PlatformComponents platformComponents,
            @NonNull final SavedStateController savedStateController,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {
        final ReconnectModule reconnectModule = createModule(ReconnectModule.class, configuration);
        reconnectModule.initialize(
                configuration,
                time,
                rosterHistory.getCurrentRoster(),
                platformComponents,
                platform,
                platformCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                reservedSignedStateResultPromise,
                selfId,
                fallenBehindMonitor);
        return reconnectModule;
    }

    @NonNull
    private StateModule createStateModule(
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final SavedStateController savedStateController) {
        return new StateModule(
                wiringModel,
                configuration,
                metrics,
                time,
                fileSystemManager,
                keysAndCerts,
                appName,
                selfId,
                swirldName,
                stateLifecycleManager,
                latestCompleteStateNexus,
                savedStateController);
    }

    @NonNull
    private TransactionHandlingModule createTransactionHandlingModule(
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @NonNull final AtomicReference<StatusActionSubmitter> statusActionSubmitterReference) {
        return new TransactionHandlingModule(
                wiringModel,
                configuration,
                metrics,
                time,
                latestImmutableStateNexus,
                consensusStateEventHandler,
                stateLifecycleManager,
                statusActionSubmitterReference,
                version,
                selfId,
                transactionOffsetNanos);
    }

    @NonNull
    private GossipModule createGossipModule(
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {
        final GossipModule module =
                requireNonNullElseGet(gossipModuleOverride, () -> createModule(GossipModule.class, configuration));
        final Supplier<ReservedSignedState> latestCompleteStateSupplier =
                () -> latestCompleteStateNexus.getState("get latest complete state for reconnect");
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                keysAndCerts,
                rosterHistory.getCurrentRoster(),
                selfId,
                version,
                intakeEventCounter,
                latestCompleteStateSupplier,
                reservedSignedStateResultPromise,
                fallenBehindMonitor,
                stateLifecycleManager);
        return module;
    }

    @NonNull
    private HashgraphModule createHashgraphModule(@Nullable final EventPipelineTracker eventPipelineTracker) {
        final HashgraphModule module = createModule(HashgraphModule.class, configuration);
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                rosterHistory.getCurrentRoster(),
                selfId,
                instant -> isInFreezePeriod(instant, stateLifecycleManager.getMutableState()),
                eventPipelineTracker,
                transactionOffsetNanos);
        return module;
    }

    @NonNull
    private Supplier<PcesReplayProgress> createPcesReplayProgressSupplier(
            @NonNull final SignedStateNexus latestImmutableStateNexus) {
        return () -> {
            try (final ReservedSignedState reservedState = latestImmutableStateNexus.getState("PCES replay")) {
                if (reservedState == null || reservedState.isNull()) {
                    return PcesReplayProgress.EMPTY;
                }
                final SignedState signedState = reservedState.get();
                return new PcesReplayProgress(signedState.getRound(), signedState.getConsensusTimestamp());
            }
        };
    }

    private void initializePcesModule(
            @NonNull final PcesModule module,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @Nullable final EventPipelineTracker eventPipelineTracker) {
        final Supplier<PcesReplayProgress> replayProgressSupplier =
                createPcesReplayProgressSupplier(latestImmutableStateNexus);
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                selfId,
                recycleBin,
                fileSystemManager,
                initialState.get().getRound(),
                platformCoordinator::flushPrimaryPipeline,
                replayProgressSupplier,
                platformCoordinator::submitStatusAction,
                platformCoordinator::flushPlatformStatus,
                platformCoordinator::signalEndOfPcesReplay,
                eventPipelineTracker);
    }

    @NonNull
    private EventPipelineTracker createEventPipelineTracker(@NonNull final EventCreatorModule eventCreatorModule) {
        final boolean eventPipelineMetricsEnabled =
                configuration.getConfigData(PlatformMetricsConfig.class).eventPipelineMetricsEnabled();
        final EventPipelineTracker eventPipelineTracker =
                eventPipelineMetricsEnabled ? new EventPipelineTracker(metrics) : null;

        // Register the event creation stage (self-only, step 1) and wire monitoring
        // before intake initialization so step numbers are sequentially.
        if (eventPipelineTracker != null) {
            eventPipelineTracker.registerMetric("eventCreation", EventOrigin.RUNTIME);
            eventCreatorModule
                    .createdEventOutputWire()
                    .solderForMonitoring(event -> eventPipelineTracker.recordEvent("eventCreation", event));
        }
        return eventPipelineTracker;
    }

    @NonNull
    private IntakeEventCounter createIntakeEventCounter() {
        if (configuration.getConfigData(SyncConfig.class).waitForEventsInIntake()) {
            return new DefaultIntakeEventCounter(rosterHistory.getCurrentRoster());
        } else {
            return new NoOpIntakeEventCounter();
        }
    }

    @NonNull
    private EventIntakeModule createEventIntakeModule(
            @NonNull final IntakeEventCounter intakeEventCounter,
            @Nullable final EventPipelineTracker eventPipelineTracker) {
        final EventIntakeModule module = createModule(EventIntakeModule.class, configuration);
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                rosterHistory,
                intakeEventCounter,
                executionLayer.getTransactionLimits(),
                eventPipelineTracker);
        return module;
    }

    @NonNull
    private EventCreatorModule createEventCreatorModule() {
        final EventCreatorModule module = createModule(EventCreatorModule.class, configuration);
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                secureRandom,
                keysAndCerts,
                rosterHistory.getCurrentRoster(),
                selfId,
                executionLayer,
                executionLayer);
        return module;
    }

    @NonNull
    private SecureRandom initializeSecureRandom(@Nullable final SecureRandom secureRandomOverride) {
        if (secureRandomOverride != null) {
            return secureRandomOverride;
        }

        try {
            return SecureRandom.getInstanceStrong();
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private WiringModel initializeWiringModel(@Nullable final WiringModel wiringModelOverride) {
        if (wiringModelOverride != null) {
            return wiringModelOverride;
        }
        final WiringConfig wiringConfig = configuration.getConfigData(WiringConfig.class);

        final int coreCount = Runtime.getRuntime().availableProcessors();
        final int parallelism = (int)
                Math.max(1, wiringConfig.defaultPoolMultiplier() * coreCount + wiringConfig.defaultPoolConstant());
        final ForkJoinPool defaultPool = executorFactory.createForkJoinPool(parallelism);
        logger.info(STARTUP.getMarker(), "Default platform pool parallelism: {}", parallelism);

        return WiringModelBuilder.create(metrics, time)
                .enableJvmAnchor()
                .withDefaultPool(defaultPool)
                .withWiringConfig(wiringConfig)
                .build();
    }

    @NonNull
    private IssDetectionModule createIssDetectionModule() {
        return new IssDetectionModule(
                wiringModel,
                configuration,
                metrics,
                time,
                rosterHistory.getCurrentRoster(),
                selfId,
                fileSystemManager,
                initialState.get().getRound(),
                latestFreezeRoundOf(initialState.get().getState()),
                SystemExitUtils::handleFatalError);
    }

    /**
     * Setup static utilities. If running multiple platforms in the same JVM and this method is called more than once
     * then this method becomes a no-op.
     *
     * @param configuration the configuration for this node
     */
    private static void doStaticSetup(@NonNull final Configuration configuration) {
        if (staticSetupCompleted) {
            // Only setup static utilities once
            return;
        }
        staticSetupCompleted = true;

        BootstrapUtils.performHealthChecks(DEFAULT_SETTINGS_PATH, configuration);
        writeSettingsUsed(configuration);

        // Initialize JVMPauseDetectorThread, if enabled via settings
        startJVMPauseDetectorThread(configuration);
    }
}
