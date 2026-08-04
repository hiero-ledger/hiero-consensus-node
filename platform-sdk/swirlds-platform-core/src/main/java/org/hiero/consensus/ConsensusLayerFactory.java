// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createModule;
import static org.hiero.consensus.platformstate.PlatformStateUtils.isInFreezePeriod;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.DefaultConsensusEventStream;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.freeze.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.nexus.DefaultLatestCompleteStateNexus;
import org.hiero.consensus.state.nexus.LatestCompleteStateNexus;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.status.StatusMonitorModule;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * A factory used to construct the consensus layer as specified in the final architecture document.
 */
public class ConsensusLayerFactory {

    private static final Logger logger = LogManager.getLogger();

    private static final UncaughtExceptionHandler DEFAULT_UNCAUGHT_EXCEPTION_HANDLER =
            (t, e) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception on thread {}: {}", t, e);

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
    private final ExecutionLayerCallbacks executionLayerCallbacks;

    @NonNull
    private final FileSystemManager fileSystemManager;

    @NonNull
    private final SemanticVersion version;

    private final long transactionOffsetNanos;

    @NonNull
    private final TransactionLimits transactionLimits;

    @Nullable
    private final ConsensusSnapshot consensusSnapshot;

    @NonNull
    private final WiringModel wiringModel;

    @NonNull
    private final SecureRandom secureRandom;

    @NonNull
    private final ExecutorFactory executorFactory;

    @NonNull
    private final Map<String, Object> additionalProperties;

    @NonNull
    private final String consensusEventStreamName;

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
        executionLayerCallbacks = inputs.executionLayerCallbacks();
        fileSystemManager = inputs.fileSystemManager();
        version = inputs.version();
        transactionOffsetNanos = inputs.transactionOffsetNanos();
        transactionLimits = inputs.transactionLimits();
        consensusSnapshot = inputs.consensusSnapshot();
        executorFactory = ExecutorFactory.create("platform", null, DEFAULT_UNCAUGHT_EXCEPTION_HANDLER);
        wiringModel = initializeWiringModel(inputs.wiringModel());
        secureRandom = initializeSecureRandom(inputs.secureRandom());
        additionalProperties = inputs.additionalProperties();
        consensusEventStreamName= inputs.consensusEventStreamName();
    }

    /**
     * Constructs most of the components and modules required to create the platform.
     *
     * @return the result of the factory, containing the platform coordinator and the building blocks
     */
    @NonNull
    public ConsensusLayerBuildingBlocks create() {
        final EventCreatorModule eventCreatorModule = createEventCreatorModule();
        final IntakeEventCounter intakeEventCounter = createIntakeEventCounter();
        final EventPipelineTracker eventPipelineTracker = createEventPipelineTracker(eventCreatorModule);
        final EventIntakeModule eventIntakeModule = createEventIntakeModule(intakeEventCounter, eventPipelineTracker);
        final FreezePeriodChecker freezePeriodChecker = new FreezePeriodChecker(null);
        final HashgraphModule hashgraphModule = createHashgraphModule(eventPipelineTracker, freezePeriodChecker);
        final LatestCompleteStateNexus latestCompleteStateNexus =
                new DefaultLatestCompleteStateNexus(configuration, metrics);
        final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise =
                new BlockingResourceProvider<>();
        final FallenBehindMonitor fallenBehindMonitor = createFallenBehindMonitor();
        final GossipModule gossipModule = createGossipModule(
                intakeEventCounter, latestCompleteStateNexus, reservedSignedStateResultPromise, fallenBehindMonitor);

        final StatusMonitorModule statusMonitorModule = createStatusMonitorModule(freezePeriodChecker);
        final PcesModule pcesModule = createModule(PcesModule.class, configuration);

        final ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring = createConsensusEventStreamWiring(
                consensusEventStreamName, freezePeriodChecker);

        final WireTransformer<EventWindow, EventWindow> initialEventWindowDispatcher = new WireTransformer<>(
                wiringModel, "InitialEventWindowDispatcher", "event window", UnaryOperator.identity());

        initializePcesModule(pcesModule, statusMonitorModule, eventIntakeModule, eventCreatorModule, hashgraphModule,
                gossipModule::flush, consensusSnapshot, eventPipelineTracker);

        ConsensusLayerStaticSetup.setup(configuration);

        final ConsensusLayerBuildingBlocks buildingBlocks = new ConsensusLayerBuildingBlocks(
                wiringModel,
                configuration,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                gossipModule,
                initialEventWindowDispatcher,
                statusMonitorModule,
                consensusEventStreamWiring,
                reservedSignedStateResultPromise,
                fallenBehindMonitor,
                intakeEventCounter,
                freezePeriodChecker);

        final ConsensusLayerImpl consensusLayer = new ConsensusLayerImpl(configuration, consensusSnapshot,
                eventIntakeModule, eventCreatorModule, gossipModule, pcesModule, hashgraphModule, statusMonitorModule,
                freezePeriodChecker);

        ConsensusLayerWiring.wire(inputs, buildingBlocks);

        initialize(buildingBlocks);
    }

    /**
     * Build the consensus event stream
     *
     * @return the consensus event stream
     */
    @NonNull
    private ComponentWiring<ConsensusEventStream, Void> createConsensusEventStreamWiring(
            @NonNull final String consensusEventStreamName, @NonNull final FreezePeriodChecker freezePeriodChecker) {
        final EventStreamWiringConfig eventStreamWiringConfig =
                configuration.getConfigData(EventStreamWiringConfig.class);
        final ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring = new ComponentWiring<>(
                wiringModel, ConsensusEventStream.class, eventStreamWiringConfig.consensusEventStream());
        final Predicate<CesEvent> isLastEventInFreezePeriod = (final CesEvent event) -> {
            final Instant consensusTimestamp = event.getConsensusTimestamp();
            return event.isLastInRoundReceived() && freezePeriodChecker.isInFreezePeriod(consensusTimestamp);
        };
        final ConsensusEventStream consensusEventStream = new DefaultConsensusEventStream(
                time,
                configuration,
                metrics,
                selfId,
                (byte[] data) -> new PlatformSigner(keysAndCerts).sign(data),
                consensusEventStreamName,
                isLastEventInFreezePeriod);
        consensusEventStreamWiring.bind(consensusEventStream);
        return consensusEventStreamWiring;
    }

    private void initialize(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        if (consensusSnapshot != null) {
            buildingBlocks
                    .hashgraphModule()
                    .consensusSnapshotOverrideInputWire()
                    .inject(consensusSnapshot);

            // We only load non-ancient events during start up, so the initial expired threshold will be
            // equal to the ancient threshold when the system first starts. Over time as we get more events,
            // the expired threshold will continue to expand until it reaches its full size.
            final int roundsNonAncient =
                    configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
            buildingBlocks
                    .initialEventWindowDispatcher()
                    .getInputWire()
                    .inject(EventWindowUtils.createEventWindow(consensusSnapshot, roundsNonAncient));
            buildingBlocks.gossipModule().flush();
        }
    }


    @NonNull
    private FallenBehindMonitor createFallenBehindMonitor() {
        final double fallenBehindThreshold =
                configuration.getConfigData(FallenBehindConfig.class).fallenBehindThreshold();
        return new FallenBehindMonitor(rosterHistory.getCurrentRoster(), selfId, fallenBehindThreshold);
    }

    @NonNull
    private StatusMonitorModule createStatusMonitorModule(@NonNull final FreezePeriodChecker freezePeriodChecker) {
        return new StatusMonitorModule(wiringModel, configuration, metrics, time, selfId, freezePeriodChecker);
    }

    @NonNull
    private GossipModule createGossipModule(
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {
        final GossipModule module = createModule(GossipModule.class, configuration);
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
                stateLifecycleManager,
                additionalProperties);
        return module;
    }

    @NonNull
    private HashgraphModule createHashgraphModule(
            @Nullable final EventPipelineTracker eventPipelineTracker,
            @NonNull final FreezePeriodChecker freezePeriodChecker) {
        final HashgraphModule module = createModule(HashgraphModule.class, configuration);
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                rosterHistory.getCurrentRoster(),
                selfId,
                freezePeriodChecker,
                eventPipelineTracker,
                transactionOffsetNanos);
        return module;
    }

    private void initializePcesModule(
            @NonNull final PcesModule module,
            @NonNull final StatusMonitorModule statusMonitorModule,
            @NonNull final EventIntakeModule eventIntakeModule,
            @NonNull final EventCreatorModule eventCreatorModule,
            @NonNull final HashgraphModule hashgraphModule,
            @NonNull final Runnable flushGossipModule,
            @Nullable final ConsensusSnapshot consensusSnapshot,
            @Nullable final EventPipelineTracker eventPipelineTracker) {
        module.initialize(
                wiringModel,
                configuration,
                metrics,
                time,
                selfId,
                recycleBin,
                fileSystemManager,
                consensusSnapshot == null ? 0 : consensusSnapshot.round(),
                statusMonitorModule,
                eventIntakeModule,
                eventCreatorModule,
                hashgraphModule,
                flushGossipModule,
                eventPipelineTracker);
    }

    @Nullable
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
                transactionLimits,
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
                executionLayerCallbacks::getTransactionsForNewEvent);
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
}
