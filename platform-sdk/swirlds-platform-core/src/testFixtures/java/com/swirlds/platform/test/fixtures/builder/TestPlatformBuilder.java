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
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
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

    // futurework: Use SPI mechanism instead
    private GossipModule gossipModuleOverride;

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
     * Overrides the gossip module for the platform being built with the specified implementation.
     * This method allows the user to provide a custom implementation of the {@link GossipModule}.
     *
     * @param gossipModuleOverride the custom {@link GossipModule} to replace the default module
     * @return this object
     */
    @NonNull
    public TestPlatformBuilder withGossipModuleOverride(@NonNull final GossipModule gossipModuleOverride) {
        throwIfAlreadyUsed();
        this.gossipModuleOverride = requireNonNull(gossipModuleOverride);
        return this;
    }

    /**
     * Provides the {@link ConsensusLayerBuildingBlocks} used to construct the consensus layer of the platform.
     *
     * @return a {@link ConsensusLayerBuildingBlocks} instance
     * @throws IllegalStateException if this builder has not been used yet to build a platform
     */
    @NonNull
    public ConsensusLayerBuildingBlocks buildingBlocks() {
        throwIfNotUsed();
        return buildingBlocks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
                wiringModel,
                secureRandom,
                gossipModuleOverride);
    }
}
