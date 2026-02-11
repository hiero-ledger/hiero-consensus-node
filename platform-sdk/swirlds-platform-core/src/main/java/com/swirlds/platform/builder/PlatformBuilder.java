// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createEventCreatorModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createEventIntakeModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createGossipModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createHashgraphModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createPcesModule;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.doStaticSetup;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.platformstate.PlatformStateUtils.isInFreezePeriod;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.iss.IssScratchpad;
import com.swirlds.platform.state.nexus.LockFreeStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.platform.wiring.PlatformWiring;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Builds a {@link SwirldsPlatform} instance.
 */
public final class PlatformBuilder {

    private static final Logger logger = LogManager.getLogger(PlatformBuilder.class);

    private final String appName;
    private final SemanticVersion softwareVersion;
    private final ReservedSignedState initialState;

    private final ConsensusStateEventHandler consensusStateEventHandler;
    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    private final NodeId selfId;
    private final String swirldName;

    private Configuration configuration;
    private ExecutorFactory executorFactory;

    private EventCreatorModule eventCreatorModule;
    private EventIntakeModule eventIntakeModule;
    private HashgraphModule hashgraphModule;
    private PcesModule pcesModule;
    private GossipModule gossipModule;

    private static final UncaughtExceptionHandler DEFAULT_UNCAUGHT_EXCEPTION_HANDLER =
            (t, e) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception on thread {}: {}", t, e);

    /**
     * A RosterHistory that allows one to lookup a roster for a given round, or get the active/previous roster.
     */
    private RosterHistory rosterHistory;

    /**
     * A consensusEventStreamName for DefaultConsensusEventStream. See javadoc and comments in
     * AddressBookUtils.formatConsensusEventStreamName() for more details.
     */
    private final String consensusEventStreamName;

    /**
     * This node's cryptographic keys.
     */
    private KeysAndCerts keysAndCerts;

    /**
     * The path to the settings file (i.e. the file with the optional settings).
     */
    private final Path settingsPath = getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME);

    /**
     * The wiring model to use for this platform.
     */
    private WiringModel model;

    /**
     * The supplier of cryptographically secure random number generators.
     */
    private Supplier<SecureRandom> secureRandomSupplier;
    /**
     * The platform context for this platform.
     */
    private PlatformContext platformContext;

    private Consumer<PlatformEvent> preconsensusEventConsumer;
    private Consumer<ConsensusSnapshot> snapshotOverrideConsumer;
    private Consumer<PlatformEvent> staleEventConsumer;
    private ExecutionLayer execution;

    /**
     * False if this builder has not yet been used to build a platform (or platform component builder), true if it has.
     */
    private boolean used;

    /**
     * Create a new platform builder.
     *
     * <p>Before calling this method, the app would try and load a state snapshot from disk. If one exists,
     * the app will pass the loaded state via the initialState argument to this method. If the snapshot doesn't exist,
     * then the app will create a new genesis state and pass it via the same initialState argument.
     *
     * @param appName the name of the application, currently used for deciding where to store states on disk
     * @param swirldName the name of the swirld, currently used for deciding where to store states on disk
     * @param softwareVersion the software version of the application
     * @param initialState the initial state supplied by the application
     * @param consensusStateEventHandler the state lifecycle events handler
     * @param selfId the ID of this node
     * @param consensusEventStreamName a part of the name of the directory where the consensus event stream is written
     * @param rosterHistory the roster history provided by the application to use at startup
     * @param stateLifecycleManager the state lifecycle manager, used to instantiate the state object from a {@link com.swirlds.virtualmap.VirtualMap} and manage the state lifecycle
     */
    @NonNull
    public static PlatformBuilder create(
            @NonNull final String appName,
            @NonNull final String swirldName,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final ReservedSignedState initialState,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final NodeId selfId,
            @NonNull final String consensusEventStreamName,
            @NonNull final RosterHistory rosterHistory,
            @NonNull final StateLifecycleManager stateLifecycleManager) {
        return new PlatformBuilder(
                appName,
                swirldName,
                softwareVersion,
                initialState,
                consensusStateEventHandler,
                selfId,
                consensusEventStreamName,
                rosterHistory,
                stateLifecycleManager);
    }

    /**
     * Constructor.
     *
     * @param appName the name of the application, currently used for deciding where to store states on disk
     * @param swirldName the name of the swirld, currently used for deciding where to store states on disk
     * @param softwareVersion the software version of the application
     * @param initialState the genesis state supplied by application
     * @param consensusStateEventHandler the state lifecycle events handler
     * @param selfId the ID of this node
     * @param consensusEventStreamName a part of the name of the directory where the consensus event stream is written
     * @param rosterHistory the roster history provided by the application to use at startup
     * @param stateLifecycleManager the state lifecycle manager, used to instantiate the state object from a {@link com.swirlds.virtualmap.VirtualMap} and manage the state lifecycle
     */
    private PlatformBuilder(
            @NonNull final String appName,
            @NonNull final String swirldName,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final ReservedSignedState initialState,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final NodeId selfId,
            @NonNull final String consensusEventStreamName,
            @NonNull final RosterHistory rosterHistory,
            @NonNull final StateLifecycleManager stateLifecycleManager) {

        this.appName = requireNonNull(appName);
        this.swirldName = requireNonNull(swirldName);
        this.softwareVersion = requireNonNull(softwareVersion);
        this.initialState = requireNonNull(initialState);
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.selfId = requireNonNull(selfId);
        this.consensusEventStreamName = requireNonNull(consensusEventStreamName);
        this.rosterHistory = requireNonNull(rosterHistory);
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);
    }

    /**
     * Provide a configuration to use for the platform. If not provided then default configuration is used.
     * <p>
     * Note that any configuration provided here must have the platform configuration properly registered.
     *
     * @param configuration the configuration to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withConfiguration(@NonNull final Configuration configuration) {
        this.configuration = requireNonNull(configuration);
        checkConfiguration(configuration);
        return this;
    }

    /**
     * Register a callback that is called when a stale self event is detected (i.e. an event that will never reach
     * consensus). Depending on the use case, it may be a good idea to resubmit the transactions in the stale event.
     * <p>
     * Stale event detection is guaranteed to catch all stale self events as long as the node remains online. However,
     * if the node restarts or reconnects, any event that went stale "in the gap" may not be detected.
     *
     * @param staleEventConsumer the callback to register
     * @return this
     */
    @NonNull
    public PlatformBuilder withStaleEventCallback(@NonNull final Consumer<PlatformEvent> staleEventConsumer) {
        throwIfAlreadyUsed();
        this.staleEventConsumer = requireNonNull(staleEventConsumer);
        return this;
    }

    @NonNull
    public PlatformBuilder withExecutionLayer(@NonNull final ExecutionLayer execution) {
        throwIfAlreadyUsed();
        this.execution = requireNonNull(execution);
        return this;
    }

    /**
     * Provide the cryptographic keys to use for this node.  The signing certificate for this node must be valid.
     *
     * @param keysAndCerts the cryptographic keys to use
     * @return this
     * @throws IllegalStateException if the signing certificate is not valid or does not match the signing private key.
     */
    @NonNull
    public PlatformBuilder withKeysAndCerts(@NonNull final KeysAndCerts keysAndCerts) {
        throwIfAlreadyUsed();
        this.keysAndCerts = requireNonNull(keysAndCerts);
        // Ensure that the platform has a valid signing cert that matches the signing private key.
        // https://github.com/hashgraph/hedera-services/issues/16648
        if (!CryptoUtils.checkCertificate(keysAndCerts.sigCert())) {
            throw new IllegalStateException("Starting the platform requires a signing cert.");
        }
        final PlatformSigner platformSigner = new PlatformSigner(keysAndCerts);
        final String testString = "testString";
        final Bytes testBytes = Bytes.wrap(testString.getBytes());
        final Signature signature = platformSigner.sign(testBytes.toByteArray());
        if (!ConsensusCryptoUtils.verifySignature(
                testBytes, signature.getBytes(), keysAndCerts.sigCert().getPublicKey())) {
            throw new IllegalStateException("The signing certificate does not match the signing private key.");
        }
        return this;
    }

    /**
     * Provide the wiring model to use for this platform.
     *
     * @param model the wiring model to use
     * @return this
     */
    public PlatformBuilder withModel(@NonNull final WiringModel model) {
        throwIfAlreadyUsed();
        this.model = requireNonNull(model);
        return this;
    }

    /**
     * Provide a supplier of cryptographically secure random number generators.
     *
     * @param secureRandomSupplier supplier of cryptographically secure random number generators
     * @return this
     */
    @NonNull
    public PlatformBuilder withSecureRandomSupplier(@NonNull final Supplier<SecureRandom> secureRandomSupplier) {
        throwIfAlreadyUsed();
        this.secureRandomSupplier = requireNonNull(secureRandomSupplier);
        return this;
    }

    /**
     * Provide the  platform context for this platform.
     *
     * @param platformContext the platform context
     * @return this
     */
    @NonNull
    public PlatformBuilder withPlatformContext(@NonNull final PlatformContext platformContext) {
        throwIfAlreadyUsed();
        this.platformContext = requireNonNull(platformContext);
        return this;
    }

    /**
     * Provide the consensus event creator to use for this platform.
     *
     * @param eventCreatorModule the consensus event creator
     * @return this
     */
    @NonNull
    public PlatformBuilder withEventCreatorModule(@NonNull final EventCreatorModule eventCreatorModule) {
        throwIfAlreadyUsed();
        this.eventCreatorModule = requireNonNull(eventCreatorModule);
        return this;
    }

    private void initializeEventCreatorModule() {
        eventCreatorModule.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                secureRandomSupplier.get(),
                keysAndCerts,
                rosterHistory.getCurrentRoster(),
                selfId,
                execution,
                execution);
    }

    /**
     * Provide the Hashgraph module to use for this platform.
     *
     * @param hashgraphModule the hashgraph module
     * @return this
     */
    @NonNull
    public PlatformBuilder withHashgraphModule(@NonNull final HashgraphModule hashgraphModule) {
        throwIfAlreadyUsed();
        this.hashgraphModule = requireNonNull(hashgraphModule);
        return this;
    }

    private void initializeHashgraphModule(@Nullable final EventPipelineTracker pipelineTracker) {
        hashgraphModule.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                rosterHistory.getCurrentRoster(),
                selfId,
                instant -> isInFreezePeriod(instant, stateLifecycleManager.getMutableState()),
                pipelineTracker);
    }

    /**
     * Provide the consensus event intake to use for this platform.
     *
     * @param eventIntakeModule the consensus event intake module
     * @return this
     */
    @NonNull
    public PlatformBuilder withEventIntakeModule(@NonNull final EventIntakeModule eventIntakeModule) {
        throwIfAlreadyUsed();
        this.eventIntakeModule = requireNonNull(eventIntakeModule);
        return this;
    }

    private void initializeEventIntakeModule(
            @NonNull final IntakeEventCounter intakeEventCounter,
            @Nullable final EventPipelineTracker pipelineTracker) {
        eventIntakeModule.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                rosterHistory,
                intakeEventCounter,
                execution.getTransactionLimits(),
                pipelineTracker);
    }

    private void initializePcesModule(
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final Supplier<ReservedSignedState> latestStateSupplier,
            @Nullable final EventPipelineTracker pipelineTracker) {
        pcesModule.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                selfId,
                platformContext.getRecycleBin(),
                initialState.get().getRound(),
                platformCoordinator::flushIntakePipeline,
                platformCoordinator::flushTransactionHandler,
                latestStateSupplier,
                pipelineTracker);
    }

    /**
     * Provide the consensus event creator to use for this platform.
     *
     * @param gossipModule the consensus event creator
     * @return this
     */
    @NonNull
    public PlatformBuilder withGossipModule(@NonNull final GossipModule gossipModule) {
        throwIfAlreadyUsed();
        this.gossipModule = requireNonNull(gossipModule);
        return this;
    }

    private void initializeGossipModule(
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final AtomicReference<Supplier<ReservedSignedState>> getLatestCompleteStateReference,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {
        if (this.gossipModule == null) {
            this.gossipModule = createGossipModule();
        }

        gossipModule.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                keysAndCerts,
                rosterHistory.getCurrentRoster(),
                selfId,
                softwareVersion,
                intakeEventCounter,
                () -> getLatestCompleteStateReference.get().get(),
                reservedSignedStateResultPromise,
                fallenBehindMonitor,
                stateLifecycleManager);
    }

    /**
     * Throw an exception if this builder has been used to build a platform or a platform factory.
     */
    private void throwIfAlreadyUsed() {
        if (used) {
            throw new IllegalStateException("PlatformBuilder has already been used");
        }
    }

    /**
     * Construct a platform component builder. This can be used for advanced use cases where custom component
     * implementations are required. If custom components are not required then {@link #build()} can be used and this
     * method can be ignored.
     *
     * @return a new platform component builder
     */
    @NonNull
    public PlatformComponentBuilder buildComponentBuilder() {
        throwIfAlreadyUsed();
        used = true;

        if (executorFactory == null) {
            executorFactory = ExecutorFactory.create("platform", null, DEFAULT_UNCAUGHT_EXCEPTION_HANDLER);
        }

        final boolean firstPlatform = doStaticSetup(configuration, settingsPath);

        final Roster currentRoster = rosterHistory.getCurrentRoster();

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final IntakeEventCounter intakeEventCounter;
        if (syncConfig.waitForEventsInIntake()) {
            intakeEventCounter = new DefaultIntakeEventCounter(currentRoster);
        } else {
            intakeEventCounter = new NoOpIntakeEventCounter();
        }

        final Scratchpad<IssScratchpad> issScratchpad =
                Scratchpad.create(platformContext.getConfiguration(), selfId, IssScratchpad.class, "platform.iss");
        issScratchpad.logContents();

        final ApplicationCallbacks callbacks =
                new ApplicationCallbacks(preconsensusEventConsumer, snapshotOverrideConsumer, staleEventConsumer);

        if (model == null) {
            final WiringConfig wiringConfig = platformContext.getConfiguration().getConfigData(WiringConfig.class);

            final int coreCount = Runtime.getRuntime().availableProcessors();
            final int parallelism = (int)
                    Math.max(1, wiringConfig.defaultPoolMultiplier() * coreCount + wiringConfig.defaultPoolConstant());
            final ForkJoinPool defaultPool =
                    platformContext.getExecutorFactory().createForkJoinPool(parallelism);
            logger.info(STARTUP.getMarker(), "Default platform pool parallelism: {}", parallelism);

            model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                    .enableJvmAnchor()
                    .withDefaultPool(defaultPool)
                    .withWiringConfig(wiringConfig)
                    .build();
        }

        if (secureRandomSupplier == null) {
            secureRandomSupplier = () -> {
                try {
                    return SecureRandom.getInstanceStrong();
                } catch (final NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        final boolean eventPipelineMetricsEnabled = platformContext
                .getConfiguration()
                .getConfigData(PlatformMetricsConfig.class)
                .eventPipelineMetricsEnabled();
        final EventPipelineTracker pipelineTracker =
                eventPipelineMetricsEnabled ? new EventPipelineTracker(platformContext.getMetrics()) : null;
        final AtomicReference<Supplier<ReservedSignedState>> getLatestCompleteStateReference = new AtomicReference<>();
        final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise =
                new BlockingResourceProvider<>();
        final FallenBehindMonitor fallenBehindMonitor =
                new FallenBehindMonitor(currentRoster, configuration, platformContext.getMetrics());

        if (this.eventCreatorModule == null) {
            this.eventCreatorModule = createEventCreatorModule();
        }
        if (this.eventIntakeModule == null) {
            this.eventIntakeModule = createEventIntakeModule();
        }
        this.pcesModule = createPcesModule();
        if (this.hashgraphModule == null) {
            this.hashgraphModule = createHashgraphModule();
        }

        final PlatformComponents platformComponents = PlatformComponents.create(
                platformContext,
                model,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                gossipModule);

        final PlatformCoordinator platformCoordinator = new PlatformCoordinator(platformComponents, callbacks);
        final SignedStateNexus latestImmutableStateNexus = new LockFreeStateNexus();

        initializeEventCreatorModule();
        initializeEventIntakeModule(intakeEventCounter, pipelineTracker);
        initializePcesModule(
                platformCoordinator, () -> latestImmutableStateNexus.getState("PCES replay"), pipelineTracker);
        initializeHashgraphModule(pipelineTracker);
        initializeGossipModule(
                intakeEventCounter,
                getLatestCompleteStateReference,
                reservedSignedStateResultPromise,
                fallenBehindMonitor);

        PlatformWiring.wire(platformContext, execution, platformComponents, callbacks);

        final PlatformBuildingBlocks buildingBlocks = new PlatformBuildingBlocks(
                platformComponents,
                platformContext,
                model,
                keysAndCerts,
                selfId,
                appName,
                swirldName,
                softwareVersion,
                initialState,
                rosterHistory,
                callbacks,
                preconsensusEventConsumer,
                snapshotOverrideConsumer,
                intakeEventCounter,
                secureRandomSupplier,
                instant -> isInFreezePeriod(instant, stateLifecycleManager.getMutableState()),
                new AtomicReference<>(),
                consensusEventStreamName,
                issScratchpad,
                NotificationEngine.buildEngine(getStaticThreadManager()),
                new AtomicReference<>(),
                stateLifecycleManager,
                getLatestCompleteStateReference,
                firstPlatform,
                consensusStateEventHandler,
                execution,
                fallenBehindMonitor,
                reservedSignedStateResultPromise,
                platformCoordinator,
                latestImmutableStateNexus);

        return new PlatformComponentBuilder(buildingBlocks);
    }

    /**
     * Build a platform. Platform is not started.
     *
     * @return a new platform instance
     */
    @NonNull
    public Platform build() {
        return buildComponentBuilder().build();
    }
}
