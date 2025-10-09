// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_CONFIG_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.doStaticSetup;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static com.swirlds.platform.event.preconsensus.PcesUtilities.getDatabaseDirectory;

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
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.freeze.FreezeCheckHolder;
import com.swirlds.platform.gossip.DefaultIntakeEventCounter;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.iss.IssScratchpad;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformWiring;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;

/**
 * Builds a {@link SwirldsPlatform} instance.
 */
public final class PlatformBuilder {

    private static final Logger logger = LogManager.getLogger(PlatformBuilder.class);

    private final String appName;
    private final SemanticVersion softwareVersion;
    private final ReservedSignedState initialState;

    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private final PlatformStateFacade platformStateFacade;
    private final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap;

    private final NodeId selfId;
    private final String swirldName;

    private Configuration configuration;
    private ExecutorFactory executorFactory;

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
     * The path to the configuration file (i.e. the file with the address book).
     */
    private final Path configPath = getAbsolutePath(DEFAULT_CONFIG_FILE_NAME);

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
     * @param platformStateFacade the facade to access the platform state
     * @param createStateFromVirtualMap a function to instantiate the state object from a Virtual Map
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
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap) {
        return new PlatformBuilder(
                appName,
                swirldName,
                softwareVersion,
                initialState,
                consensusStateEventHandler,
                selfId,
                consensusEventStreamName,
                rosterHistory,
                platformStateFacade,
                createStateFromVirtualMap);
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
     * @param platformStateFacade the facade to access the platform state
     * @param createStateFromVirtualMap a function to instantiate the state object from a Virtual Map
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
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap) {

        this.appName = Objects.requireNonNull(appName);
        this.swirldName = Objects.requireNonNull(swirldName);
        this.softwareVersion = Objects.requireNonNull(softwareVersion);
        this.initialState = Objects.requireNonNull(initialState);
        this.consensusStateEventHandler = Objects.requireNonNull(consensusStateEventHandler);
        this.selfId = Objects.requireNonNull(selfId);
        this.consensusEventStreamName = Objects.requireNonNull(consensusEventStreamName);
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        this.platformStateFacade = Objects.requireNonNull(platformStateFacade);
        this.createStateFromVirtualMap = Objects.requireNonNull(createStateFromVirtualMap);
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
        this.configuration = Objects.requireNonNull(configuration);
        checkConfiguration(configuration);
        return this;
    }

    /**
     * Registers a callback that is called for each valid non-ancient preconsensus event in topological order (i.e.
     * after each event exits the orphan buffer). Useful for scenarios where access to this internal stream of events is
     * useful (e.g. UI hashgraph visualizers).
     *
     * <p>
     * Among all callbacks in the following list, it is guaranteed that callbacks will not be called concurrently, and
     * that there will be a happens-before relationship between each of the callbacks.
     *
     * <ul>
     *     <li>{@link #withPreconsensusEventCallback(Consumer)} (i.e. this callback)</li>
     *     <li>{@link #withConsensusSnapshotOverrideCallback(Consumer)}</li>
     * </ul>
     *
     * @param preconsensusEventConsumer the callback to register
     * @return this
     */
    @NonNull
    public PlatformBuilder withPreconsensusEventCallback(
            @NonNull final Consumer<PlatformEvent> preconsensusEventConsumer) {
        throwIfAlreadyUsed();
        this.preconsensusEventConsumer = Objects.requireNonNull(preconsensusEventConsumer);
        return this;
    }

    /**
     * Registers a callback that is called when the consensus snapshot is specified by an out of band operation (i.e.
     * restart or reconnect). Useful for scenarios where access to this internal stream of data is useful (e.g. UI
     * hashgraph visualizers).
     *
     * <p>
     * Among all callbacks in the following list, it is guaranteed that callbacks will not be called concurrently, and
     * that there will be a happens-before relationship between each of the callbacks.
     *
     * <ul>
     *     <li>{@link #withPreconsensusEventCallback(Consumer)}</li>
     *     <li>{@link #withConsensusSnapshotOverrideCallback(Consumer)} (i.e. this callback)</li>
     * </ul>
     *
     * @return this
     */
    @NonNull
    public PlatformBuilder withConsensusSnapshotOverrideCallback(
            @NonNull final Consumer<ConsensusSnapshot> snapshotOverrideConsumer) {
        throwIfAlreadyUsed();
        this.snapshotOverrideConsumer = Objects.requireNonNull(snapshotOverrideConsumer);
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
        this.staleEventConsumer = Objects.requireNonNull(staleEventConsumer);
        return this;
    }

    @NonNull
    public PlatformBuilder withExecutionLayer(@NonNull final ExecutionLayer execution) {
        throwIfAlreadyUsed();
        this.execution = Objects.requireNonNull(execution);
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
        this.keysAndCerts = Objects.requireNonNull(keysAndCerts);
        // Ensure that the platform has a valid signing cert that matches the signing private key.
        // https://github.com/hashgraph/hedera-services/issues/16648
        if (!CryptoUtils.checkCertificate(keysAndCerts.sigCert())) {
            throw new IllegalStateException("Starting the platform requires a signing cert.");
        }
        final PlatformSigner platformSigner = new PlatformSigner(keysAndCerts);
        final String testString = "testString";
        final Bytes testBytes = Bytes.wrap(testString.getBytes());
        final Signature signature = platformSigner.sign(testBytes.toByteArray());
        if (!CryptoStatic.verifySignature(
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
        this.model = Objects.requireNonNull(model);
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
        this.secureRandomSupplier = Objects.requireNonNull(secureRandomSupplier);
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
        this.platformContext = Objects.requireNonNull(platformContext);
        return this;
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

        final boolean firstPlatform = doStaticSetup(configuration, configPath);

        final Roster currentRoster = rosterHistory.getCurrentRoster();

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final IntakeEventCounter intakeEventCounter;
        if (syncConfig.waitForEventsInIntake()) {
            intakeEventCounter = new DefaultIntakeEventCounter(currentRoster);
        } else {
            intakeEventCounter = new NoOpIntakeEventCounter();
        }

        final PcesConfig preconsensusEventStreamConfig =
                platformContext.getConfiguration().getConfigData(PcesConfig.class);

        final PcesFileTracker initialPcesFiles;
        try {
            final Path databaseDirectory = getDatabaseDirectory(platformContext.getConfiguration(), selfId);

            // When we perform the migration to using birth round bounding, we will need to read
            // the old type and start writing the new type.
            initialPcesFiles = PcesFileReader.readFilesFromDisk(
                    platformContext.getConfiguration(),
                    platformContext.getRecycleBin(),
                    databaseDirectory,
                    initialState.get().getRound(),
                    preconsensusEventStreamConfig.permitGaps());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        final Scratchpad<IssScratchpad> issScratchpad =
                Scratchpad.create(platformContext.getConfiguration(), selfId, IssScratchpad.class, "platform.iss");
        issScratchpad.logContents();

        final ApplicationCallbacks callbacks =
                new ApplicationCallbacks(preconsensusEventConsumer, snapshotOverrideConsumer, staleEventConsumer);

        final AtomicReference<StatusActionSubmitter> statusActionSubmitterAtomicReference = new AtomicReference<>();
        final SwirldStateManager swirldStateManager = new SwirldStateManager(
                platformContext,
                currentRoster,
                selfId,
                x -> statusActionSubmitterAtomicReference.get().submitStatusAction(x),
                softwareVersion,
                consensusStateEventHandler,
                platformStateFacade);

        if (model == null) {
            final WiringConfig wiringConfig = platformContext.getConfiguration().getConfigData(WiringConfig.class);

            final int coreCount = Runtime.getRuntime().availableProcessors();
            final int parallelism = (int)
                    Math.max(1, wiringConfig.defaultPoolMultiplier() * coreCount + wiringConfig.defaultPoolConstant());
            final ForkJoinPool defaultPool =
                    platformContext.getExecutorFactory().createForkJoinPool(parallelism);
            logger.info(STARTUP.getMarker(), "Default platform pool parallelism: {}", parallelism);

            model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                    .withJvmAnchorEnabled(true)
                    .withDefaultPool(defaultPool)
                    .withHealthMonitorEnabled(wiringConfig.healthMonitorEnabled())
                    .withHardBackpressureEnabled(wiringConfig.hardBackpressureEnabled())
                    .withHealthMonitorCapacity(wiringConfig.healthMonitorSchedulerCapacity())
                    .withHealthMonitorPeriod(wiringConfig.healthMonitorHeartbeatPeriod())
                    .withHealthLogThreshold(wiringConfig.healthLogThreshold())
                    .withHealthLogPeriod(wiringConfig.healthLogPeriod())
                    .withHealthyReportThreshold(wiringConfig.healthyReportThreshold())
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

        var platformComponentWiring = PlatformComponents.create(platformContext, model, callbacks);

        PlatformWiring.wire(platformContext, execution, platformComponentWiring);

        final PlatformBuildingBlocks buildingBlocks = new PlatformBuildingBlocks(
                platformComponentWiring,
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
                new FreezeCheckHolder(),
                new AtomicReference<>(),
                initialPcesFiles,
                consensusEventStreamName,
                issScratchpad,
                NotificationEngine.buildEngine(getStaticThreadManager()),
                statusActionSubmitterAtomicReference,
                swirldStateManager,
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                firstPlatform,
                consensusStateEventHandler,
                platformStateFacade,
                execution,
                createStateFromVirtualMap);

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
