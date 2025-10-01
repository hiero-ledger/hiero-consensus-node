// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getGlobalMetrics;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.DefaultSwirldMain;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * A testing app for guaranteeing proper handling of transactions after a restart
 */
public class ConsistencyTestingToolMain extends DefaultSwirldMain<ConsistencyTestingToolState> {

    private static final Logger logger = LogManager.getLogger(ConsistencyTestingToolMain.class);

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    static {
        logger.info(STARTUP.getMarker(), "Registering MerkleStateRoot Class Ids with ConstructableRegistry...");
        registerMerkleStateRootClassIds();
        logger.info(STARTUP.getMarker(), " MerkleStateRoot Class Ids are registered with the ConstructableRegistry!");
    }

    /**
     * The platform instance
     */
    private Platform platform;

    /**
     * The number of transactions to generate per second.
     */
    private static final int TRANSACTIONS_PER_SECOND = 100;

    /**
     * Constructor
     */
    public ConsistencyTestingToolMain() {
        logger.info(STARTUP.getMarker(), "constructor called in Main.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId);

        this.platform = Objects.requireNonNull(platform);
        logger.info(STARTUP.getMarker(), "init called in Main for node {}.", nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        logger.info(STARTUP.getMarker(), "run called in Main.");
        new TransactionGenerator(new SecureRandom(), platform, getTransactionPool(), TRANSACTIONS_PER_SECOND).start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsistencyTestingToolState newStateRoot() {
        return createConsistencyTestingToolState(createVirtualMap());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Function<VirtualMap, ConsistencyTestingToolState> stateRootFromVirtualMap() {
        return this::createConsistencyTestingToolState;
    }

    /**
     * Creates a new ConsistencyTestingToolState with the given VirtualMap and initializes
     * the consensus module states.
     *
     * @param virtualMap the virtual map to use for the state
     * @return a new initialized ConsistencyTestingToolState
     */
    private ConsistencyTestingToolState createConsistencyTestingToolState(@NonNull final VirtualMap virtualMap) {
        final ConsistencyTestingToolState state = new ConsistencyTestingToolState(virtualMap);
        TestingAppStateInitializer.initConsensusModuleStates(state);
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusStateEventHandler<ConsistencyTestingToolState> newConsensusStateEvenHandler() {
        return new ConsistencyTestingToolConsensusStateEventHandler(new PlatformStateFacade());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SemanticVersion getSemanticVersion() {
        logger.info(STARTUP.getMarker(), "returning software version {}", semanticVersion);
        return semanticVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public List<Class<? extends Record>> getConfigDataTypes() {
        return List.of(ConsistencyTestingToolConfig.class);
    }

    /**
     * Create a virtual map for the case of genesis state initialization via {@link ConsistencyTestingToolMain#newStateRoot()}
     *
     * @return pre-configured empty Virtual Map with metrics
     */
    private static VirtualMap createVirtualMap() {
        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final VirtualDataSourceBuilder dsBuilder =
                new MerkleDbDataSourceBuilder(configuration, 1_000_000, merkleDbConfig.hashesRamToDiskThreshold());
        final VirtualMap virtualMap = new VirtualMap("ConsistencyTestingTool", dsBuilder, configuration);
        virtualMap.registerMetrics(getGlobalMetrics());
        return virtualMap;
    }
}
