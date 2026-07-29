// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.builder;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.util.Map;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.ConsensusLayerAdapterInputs;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * The {@code TestPlatformBuilder} class is a specialized implementation of {@link PlatformBuilder} for building
 * test platforms with customizable components and configurations. This builder allows for fine-grained control
 * over platform construction, enabling the user to modify or override specific elements such as the wiring model,
 * secure random generator, and gossip module.
 */
public class TestPlatformBuilder extends PlatformBuilder<TestPlatformBuilder> {

    private WiringModel wiringModel;

    private SecureRandom secureRandom;

    private Map<String, Object> additionalProperties;

    /**
     * Constructs a TestPlatformBuilder instance with the specified configuration and components.
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
     * @param stateLifecycleManager The lifecycle manager for managing state transitions.
     * @param softwareVersion The software version of the application.
     * @param persistenceScope The application name and swirld name for determining where to store states on disk.
     * @param consensusEventStreamName A part of the name of the directory where the consensus event stream is written.
     * @param transactionOffsetNanos The nanosecond offset added to the first transaction's timestamp in each event.
     */
    public TestPlatformBuilder(
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
        super(
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
                persistenceScope,
                consensusEventStreamName,
                transactionOffsetNanos);
    }

    /**
     * Sets a custom {@link WiringModel} to be used in the platform being built.
     *
     * @param wiringModel the {@link WiringModel}
     * @return this object
     */
    @NonNull
    public TestPlatformBuilder withWiringModel(@NonNull final WiringModel wiringModel) {
        throwIfAlreadyUsed();
        this.wiringModel = requireNonNull(wiringModel);
        return this;
    }

    /**
     * Sets a custom {@link SecureRandom} instance to be used in the platform being built.
     *
     * @param secureRandom the {@link SecureRandom} instance to use for random number generation
     * @return this object
     */
    @NonNull
    public TestPlatformBuilder withSecureRandom(@NonNull final SecureRandom secureRandom) {
        throwIfAlreadyUsed();
        this.secureRandom = requireNonNull(secureRandom);
        return this;
    }

    /**
     * Sets additional properties to be passed to modules.
     *
     * @return this object
     */
    @NonNull
    public TestPlatformBuilder withAdditionalProperties(@NonNull final Map<String, Object> additionalProperties) {
        throwIfAlreadyUsed();
        this.additionalProperties = requireNonNull(additionalProperties);
        return this;
    }

    /**
     * Provides the {@link ConsensusLayerAdapterBuildingBlocks} used to construct the consensus layer of the platform.
     *
     * @return a {@link ConsensusLayerAdapterBuildingBlocks} instance
     * @throws IllegalStateException if this builder has not been used yet to build a platform
     */
    @NonNull
    public ConsensusLayerAdapterBuildingBlocks buildingBlocks() {
        throwIfNotUsed();
        return buildingBlocks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
                wiringModel,
                secureRandom,
                additionalProperties);
    }
}
