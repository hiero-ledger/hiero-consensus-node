// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
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
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerFactory;
import org.hiero.consensus.ConsensusLayerFactory.ConsensusLayerFactoryResult;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Builds a {@link SwirldsPlatform} instance.
 */
public class PlatformBuilder<T extends PlatformBuilder<T>> {

    private static final Logger logger = LogManager.getLogger(PlatformBuilder.class);

    public record PersistenceScope(
            @NonNull String appName, @NonNull String swirldName) {}

    protected final Configuration configuration;
    protected final Metrics metrics;
    protected final Time time;

    /**
     * A RosterHistory that allows one to lookup a roster for a given round, or get the active/previous roster.
     */
    protected final RosterHistory rosterHistory;

    protected final NodeId selfId;

    /**
     * This node's cryptographic keys.
     */
    protected final KeysAndCerts keysAndCerts;

    protected final FileSystemManager fileSystemManager;

    protected final RecycleBin recycleBin;

    protected final ExecutionLayer executionLayer;

    protected final ConsensusStateEventHandler consensusStateEventHandler;

    protected final ReservedSignedState initialState;

    protected final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    protected final SemanticVersion softwareVersion;

    protected final String appName;

    protected final String swirldName;

    /**
     * A consensusEventStreamName for DefaultConsensusEventStream. See javadoc and comments in
     * AddressBookUtils.formatConsensusEventStreamName() for more details.
     */
    protected final String consensusEventStreamName;

    protected final long transactionOffsetNanos;

    protected StaleEventConsumer staleEventConsumer;

    protected ConsensusLayerBuildingBlocks buildingBlocks;

    /**
     * False if this builder has not yet been used to build a platform (or platform component builder), true if it has.
     */
    private boolean used = false;

    /**
     * Constructs a PlatformBuilder instance with the specified configuration and components.
     *
     * @param configuration The system configuration to use for the platform.
     * @param metrics The metrics registry used for tracking performance and statistics.
     * @param time The time provider for managing timestamps and time-sensitive operations.
     * @param rosterHistory The history of the network roster for the platform.
     * @param keysAndCerts The cryptographic keys and certificates for securing platform operations.
     * @param selfId The unique identifier of the node within the platform.
     * @param recycleBin The recycle bin for managing discarded resources and metadata.
     * @param fileSystemManager The file system manager responsible for handling file operations.
     * @param executionLayer The execution layer responsible for application-specific processing.
     * @param consensusStateEventHandler The handler for processing consensus-related events.
     * @param initialState The initial state of the platform.
     * @param stateLifecycleManager The lifecycle manager for managing state transitions.
     * @param softwareVersion The version of the software being executed on the platform.
     * @param persistenceScope The scope for persisted data used by the platform.
     * @param consensusEventStreamName The name of the consensus event stream for logging purposes.
     * @param transactionOffsetNanos The offset in nanoseconds for transaction timestamps.
     */
    public PlatformBuilder(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final RosterHistory rosterHistory,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final NodeId selfId,
            @NonNull final RecycleBin recycleBin,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final ExecutionLayer executionLayer,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final ReservedSignedState initialState,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final PersistenceScope persistenceScope,
            @NonNull final String consensusEventStreamName,
            final long transactionOffsetNanos) {

        checkConfiguration(configuration);
        checkKeysAndCerts(keysAndCerts);

        this.configuration = requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.rosterHistory = requireNonNull(rosterHistory);
        this.selfId = requireNonNull(selfId);
        this.keysAndCerts = requireNonNull(keysAndCerts);
        this.fileSystemManager = requireNonNull(fileSystemManager);
        this.recycleBin = requireNonNull(recycleBin);
        this.executionLayer = requireNonNull(executionLayer);
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.initialState = requireNonNull(initialState);
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);
        this.softwareVersion = requireNonNull(softwareVersion);
        this.appName = requireNonNull(persistenceScope.appName);
        this.swirldName = requireNonNull(persistenceScope.swirldName);
        this.consensusEventStreamName = requireNonNull(consensusEventStreamName);
        this.transactionOffsetNanos = transactionOffsetNanos;

        logger.info(STARTUP.getMarker(), "Starting with roster history:\n{}", rosterHistory);
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
        final ConsensusLayerInputs inputs = createConsensusLayerInputs();
        final ConsensusLayerFactory factory = new ConsensusLayerFactory(inputs);
        final ConsensusLayerFactoryResult factoryOutput = factory.create();

        buildingBlocks = factoryOutput.consensusLayerBuildingBlocks();
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
        final boolean reconnectActive =
                configuration.getConfigData(ReconnectConfig.class).active();
        if (reconnectActive) {
            factory.setupReconnectModule(
                    platform,
                    platformCoordinator,
                    buildingBlocks.platformComponents(),
                    buildingBlocks.savedStateController(),
                    buildingBlocks.reservedSignedStateResultPromise(),
                    buildingBlocks.fallenBehindMonitor());
        }

        // Close the initial reservation made on this state, taken in {@link StartupStateUtils#loadInitialState}
        initialState.close();

        // FutureWork figure out if this can be moved into Platform.start()
        getMetricsProvider().start();

        return platform;
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
    @SuppressWarnings("unchecked")
    @NonNull
    public T withStaleEventConsumer(@NonNull final StaleEventConsumer staleEventConsumer) {
        throwIfAlreadyUsed();
        this.staleEventConsumer = requireNonNull(staleEventConsumer);
        return (T) this;
    }

    /**
     * Throw an exception if this builder has been used to build a platform.
     */
    protected void throwIfAlreadyUsed() {
        if (used) {
            throw new IllegalStateException("PlatformBuilder has already been used");
        }
    }

    /**
     * Throw an exception if this builder has NOT been used yet to build a platform.
     */
    protected void throwIfNotUsed() {
        if (!used) {
            throw new IllegalStateException("PlatformBuilder has not been used yet");
        }
    }

    /**
     * Creates and returns an instance of {@link ConsensusLayerInputs} configured with the necessary
     * dependencies and settings required for initializing the consensus layer of the platform.
     *
     * @return a fully-constructed {@link ConsensusLayerInputs} instance
     */
    @NonNull
    protected ConsensusLayerInputs createConsensusLayerInputs() {
        return new ConsensusLayerInputs(
                configuration,
                metrics,
                time,
                rosterHistory,
                keysAndCerts,
                selfId,
                recycleBin,
                fileSystemManager,
                executionLayer,
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
    }

    /**
     * Check the cryptographic keys for this node. The signing certificate for this node must be valid.
     *
     * @param keysAndCerts the cryptographic keys to use
     * @throws IllegalStateException if the signing certificate is not valid or does not match the signing private key.
     */
    private static void checkKeysAndCerts(@NonNull final KeysAndCerts keysAndCerts) {
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
    }
}
