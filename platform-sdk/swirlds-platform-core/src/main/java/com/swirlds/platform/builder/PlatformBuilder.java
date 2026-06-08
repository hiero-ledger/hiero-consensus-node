// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.StaleEventConsumer;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.platform.wiring.PlatformWiring;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerFactory;
import org.hiero.consensus.ConsensusLayerFactory.ConsensusLayerFactoryResult;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

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
    private long transactionOffsetNanos;

    /**
     * A RosterHistory that allows one to lookup a roster for a given round, or get the active/previous roster.
     */
    private final RosterHistory rosterHistory;

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
     * The platform context for this platform.
     */
    private PlatformContext platformContext;

    private StaleEventConsumer staleEventConsumer;
    private ExecutionLayer execution;

    /**
     * False if this builder has not yet been used to build a platform (or platform component builder), true if it has.
     */
    private boolean used;
    private ConsensusLayerFactory factory;

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
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {
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
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {

        this.appName = requireNonNull(appName);
        this.swirldName = requireNonNull(swirldName);
        this.softwareVersion = requireNonNull(softwareVersion);
        this.initialState = requireNonNull(initialState);
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.selfId = requireNonNull(selfId);
        this.consensusEventStreamName = requireNonNull(consensusEventStreamName);
        this.rosterHistory = requireNonNull(rosterHistory);
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);

        logger.info(STARTUP.getMarker(), "Starting with roster history:\n{}", rosterHistory);
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
    public PlatformBuilder withStaleEventConsumer(@NonNull final StaleEventConsumer staleEventConsumer) {
        throwIfAlreadyUsed();
        this.staleEventConsumer = requireNonNull(staleEventConsumer);
        return this;
    }

    /**
     * Provide the execution layer instance used by the consensus layer to interact with the execution layer.
     * @param execution the execution layer instance
     * @return this
     */
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
        if (!CryptoUtils.verifySignature(
                testBytes, signature.getBytes(), keysAndCerts.sigCert().getPublicKey())) {
            throw new IllegalStateException("The signing certificate does not match the signing private key.");
        }
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
     * Set the nanosecond offset added to the first transaction's timestamp in each event. This value is
     * computed by the execution layer and must be provided before building the platform.
     *
     * @param transactionOffsetNanos nanoseconds to add to the first transaction's timestamp in an event
     * @return this
     */
    @NonNull
    public PlatformBuilder withTransactionOffsetNanos(final long transactionOffsetNanos) {
        throwIfAlreadyUsed();
        this.transactionOffsetNanos = transactionOffsetNanos;
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
     * Build a platform. Platform is not started.
     *
     * @return a new platform instance
     */
    @NonNull
    public Platform build() {
        throwIfAlreadyUsed();
        used = true;
        final ConsensusLayerInputs inputs = new ConsensusLayerInputs(
                configuration,
                platformContext.getMetrics(),
                platformContext.getTime(),
                rosterHistory,
                keysAndCerts,
                selfId,
                platformContext.getRecycleBin(),
                platformContext.getFileSystemManager(),
                execution,
                consensusStateEventHandler,
                initialState,
                stateLifecycleManager,
                softwareVersion,
                appName,
                swirldName,
                consensusEventStreamName,
                transactionOffsetNanos,
                staleEventConsumer,
                null,
                null,
                null);
        factory = new ConsensusLayerFactory(inputs);
        final ConsensusLayerFactoryResult factoryOutput = factory.create();

        final ConsensusLayerBuildingBlocks buildingBlocks = factoryOutput.consensusLayerBuildingBlocks();
        final PlatformCoordinator platformCoordinator = factoryOutput.platformCoordinator();

        PlatformWiring.wire(inputs, buildingBlocks);

        final SignedState initialSignedState = initialState.get();
        final boolean startedFromGenesis = initialSignedState.isGenesisState();

        final SwirldsPlatform platform;
        if (startedFromGenesis) {
            platform = new SwirldsPlatform(inputs, platformCoordinator, buildingBlocks, 0, 0);
        } else {
            final long initialAncientThreshold = ancientThresholdOf(initialSignedState.getState());
            platform = new SwirldsPlatform(
                    inputs,
                    platformCoordinator,
                    buildingBlocks,
                    initialAncientThreshold,
                    initialSignedState.getRound());
        }

        InitialStateLoader.initializeModulesWithInitialState(platform, inputs, buildingBlocks, platformCoordinator);

        // Future work - capture the reconnect module, add a start() method to it, and call it later
        factory.createReconnectModule(
                platform,
                platformCoordinator,
                buildingBlocks.platformComponents(),
                buildingBlocks.savedStateController(),
                buildingBlocks.reservedSignedStateResultPromise(),
                buildingBlocks.fallenBehindMonitor());

        // Close the initial reservation made on this state, taken in {@link StartupStateUtils#loadInitialState}
        initialState.close();

        // FutureWork figure out if this can be moved into Platform.start()
        getMetricsProvider().start();

        return platform;
    }

    public ConsensusLayerFactory getConsensusLayerFactory() {
        return factory;
    }
}
