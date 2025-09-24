// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.DefaultSwirldMain;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;

/**
 * A testing app for guaranteeing proper handling of transactions after a restart
 */
public class ConsistencyTestingToolMain extends DefaultSwirldMain<ConsistencyTestingToolState> {

    private static final Logger logger = LogManager.getLogger(ConsistencyTestingToolMain.class);

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering ConsistencyTestingToolState with ConstructableRegistry");
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(
                    new ClassConstructorPair(ConsistencyTestingToolState.class, () -> {
                        ConsistencyTestingToolState consistencyTestingToolState = new ConsistencyTestingToolState();
                        // Don't call FAKE_MERKLE_STATE_LIFECYCLES.initStates(consistencyTestingToolState) here.
                        // The stub states are automatically initialized upon loading the state from disk,
                        // or after finishing a reconnect.
                        return consistencyTestingToolState;
                    }));
            registerMerkleStateRootClassIds();
            logger.info(STARTUP.getMarker(), "ConsistencyTestingToolState is registered with ConstructableRegistry");
        } catch (ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register ConsistencyTestingToolState", e);
            throw new RuntimeException(e);
        }
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
        final ConsistencyTestingToolState state = new ConsistencyTestingToolState();
        TestingAppStateInitializer.DEFAULT.initConsensusModuleStates(state);

        return state;
    }

    /**
     * {@inheritDoc}
     * <p>
     * FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19002
     * </p>
     */
    @Override
    public Function<VirtualMap, ConsistencyTestingToolState> stateRootFromVirtualMap() {
        throw new UnsupportedOperationException();
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
}
