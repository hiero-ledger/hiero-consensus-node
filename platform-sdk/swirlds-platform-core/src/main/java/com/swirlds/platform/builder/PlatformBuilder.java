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
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.Signature;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.ConsensusLayerAdapterFactory;
import org.hiero.consensus.ConsensusLayerAdapterInputs;
import org.hiero.consensus.ConsensusLayerAdapterWiring;
import org.hiero.consensus.ConsensusLayerWiring;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Builds a {@link SwirldsPlatform} instance.
 */
public class PlatformBuilder<T extends PlatformBuilder<T>> {

    private static final Logger logger = LogManager.getLogger(PlatformBuilder.class);

    /**
     * A record representing the persistence scope, which includes the application name and swirld name.
     *
     * @param appName the name of the application
     * @param swirldName the name of the swirld
     */
    public record PersistenceScope(
            @NonNull String appName, @NonNull String swirldName) {}

    /** The configuration settings for the platform. */
    protected final Configuration configuration;

    /** The metrics system for monitoring and reporting platform performance. */
    protected final Metrics metrics;

    /** The time source for the platform, used for timestamping events and transactions. */
    protected final Time time;

    /** The roster history provided by the application to use at startup. */
    protected final RosterHistory rosterHistory;

    /** The unique identifier of this node within the network. */
    protected final NodeId selfId;

    /** This node's cryptographic keys, used for signing and verifying messages. */
    protected final KeysAndCerts keysAndCerts;

    /** The file system manager responsible for handling file operations. */
    protected final FileSystemManager fileSystemManager;

    /** The recycle bin, which stores deleted files before they are permanently deleted. */
    protected final RecycleBin recycleBin;

    /** The execution layer called for application-specific processing. */
    protected final ExecutionLayer executionLayer;

    /** The handler for processing consensus-related events. */
    protected final ConsensusStateEventHandler consensusStateEventHandler;

    /** The initial state supplied by the application. */
    protected final ReservedSignedState initialState;

    /** The lifecycle manager for managing the state lifecycle. */
    protected final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    /** The software version of the application. */
    protected final SemanticVersion softwareVersion;

    /** The name of the application, currently used for deciding where to store states on disk */
    protected final String appName;

    /** The name of the swirld, currently used for deciding where to store states on disk */
    protected final String swirldName;

    /** A part of the name of the directory where the consensus event stream is written. */
    protected final String consensusEventStreamName;

    /** The nanosecond offset added to the first transaction's timestamp in each event. */
    protected final long transactionOffsetNanos;

    /** A callback that is called when a stale self event is detected. */
    protected StaleEventConsumer staleEventConsumer = _ -> {};

    /** The building blocks used to construct the consensus layer. */
    protected ConsensusLayerAdapterBuildingBlocks buildingBlocks;

    /** False if this builder has not yet been used to build a platform, true if it has. */
    private boolean used = false;

    /**
     * Constructs a PlatformBuilder instance with the specified configuration and components.
     *
     * @param configuration The configuration settings for the platform.
     * @param metrics The metrics system for monitoring and reporting platform performance.
     * @param time The time source for the platform, used for timestamping events and transactions.
     * @param rosterHistory The roster history provided by the application to use at startup.
     * @param keysAndCerts The cryptographic keys and certificates for the node, used for signing and verifying messages.
     * @param selfId The unique identifier of the node within the network.
     * @param recycleBin The recycle bin, which stores deleted files before they are permanently deleted.
     * @param fileSystemManager The file system manager responsible for handling file operations.
     * @param executionLayer The execution layer called for application-specific processing.
     * @param consensusStateEventHandler The handler for processing consensus-related events.
     * @param initialState The initial state supplied by the application.
     * @param stateLifecycleManager The lifecycle manager for managing the state lifecycle.
     * @param softwareVersion The software version of the application.
     * @param persistenceScope The application name and swirld name for determining where to store states on disk.
     * @param consensusEventStreamName A part of the name of the directory where the consensus event stream is written.
     * @param transactionOffsetNanos The nanosecond offset added to the first transaction's timestamp in each event.
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
        final ConsensusLayerAdapterInputs inputs = createConsensusLayerInputs();
        final ConsensusLayerAdapterFactory factory = new ConsensusLayerAdapterFactory(inputs);
        buildingBlocks = factory.create();

        ConsensusLayerAdapterWiring.wire(inputs, buildingBlocks);

        final SwirldsPlatform platform = new SwirldsPlatform(inputs, buildingBlocks);

        InitialStateLoader.initializeModulesWithInitialState(platform, inputs, buildingBlocks);

        // Future work - capture the reconnect module, add a start() method to it, and call it later
        final boolean reconnectActive = configuration.getConfigData(ReconnectConfig.class).active();
        if (reconnectActive) {
            factory.setupReconnectModule(platform, buildingBlocks);
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
     * Creates and returns an instance of {@link ConsensusLayerAdapterInputs} configured with the necessary
     * dependencies and settings required for initializing the consensus layer of the platform.
     *
     * @return a fully-constructed {@link ConsensusLayerAdapterInputs} instance
     */
    @NonNull
    protected ConsensusLayerAdapterInputs createConsensusLayerInputs() {
        return new ConsensusLayerAdapterInputs(
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
                Map.of());
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
