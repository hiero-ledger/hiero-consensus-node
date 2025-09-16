// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getGlobalMetrics;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.DefaultSwirldMain;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.state.notifications.IssListener;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification;

/**
 * An application that can be made to ISS in controllable ways.
 * <p>
 * A log error can also be scheduled to be written. This is useful because it's' possible that not all nodes learn
 * about an ISS, since nodes stop gossiping when they detect the ISS. Slow nodes may not detect the ISS before their
 * peers stop gossiping. Therefore, we can validate that a scheduled log error doesn't occur, due to consensus coming to
 * a halt, even if an ISS isn't detected.
 */
public class ISSTestingToolMain extends DefaultSwirldMain<ISSTestingToolState> {

    private static final Logger logger = LogManager.getLogger(ISSTestingToolMain.class);

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    static {
        logger.info(STARTUP.getMarker(), "Registering MerkleStateRoot Class Ids with ConstructableRegistry...");
        registerMerkleStateRootClassIds();
        logger.info(STARTUP.getMarker(), " MerkleStateRoot Class Ids are registered with the ConstructableRegistry!");
    }

    private Platform platform;

    /**
     * Constructor
     */
    public ISSTestingToolMain() {
        logger.info(STARTUP.getMarker(), "constructor called in Main.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final Platform platform, final NodeId id) {
        this.platform = platform;
        platform.getNotificationEngine().register(IssListener.class, this::issListener);
    }

    /**
     * Called when there is an ISS.
     */
    private void issListener(final IssNotification notification) {
        // Quan: this is a good place to write logs that the validators catch
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        final ISSTestingToolConfig testingToolConfig =
                platform.getContext().getConfiguration().getConfigData(ISSTestingToolConfig.class);

        new TransactionGenerator(
                        new Random(), platform, getTransactionPool(), testingToolConfig.transactionsPerSecond())
                .start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ISSTestingToolState newStateRoot() {
        final ISSTestingToolState state = new ISSTestingToolState(createVirtualMap());
        TestingAppStateInitializer.DEFAULT.initStates(state);
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Function<VirtualMap, ISSTestingToolState> stateRootFromVirtualMap() {
        return (virtualMap) -> {
            final ISSTestingToolState state = new ISSTestingToolState(virtualMap);
            TestingAppStateInitializer.DEFAULT.initStates(state);
            return state;
        };
    }

    @Override
    public ConsensusStateEventHandler<ISSTestingToolState> newConsensusStateEvenHandler() {
        return new ISSTestingToolConsensusStateEventHandler();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        return semanticVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public List<Class<? extends Record>> getConfigDataTypes() {
        return List.of(ISSTestingToolConfig.class);
    }

    /**
     * Create a virtual map for the case of genesis state initialization via {@link ISSTestingToolMain#newStateRoot()}
     *
     * @return pre-configured empty Virtual Map with metrics
     */
    private static VirtualMap createVirtualMap() {
        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final VirtualDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(configuration, 1_000_000, merkleDbConfig.hashesRamToDiskThreshold());
        final VirtualMap virtualMap = new VirtualMap("VMM-ISS-TT", dsBuilder, configuration);
        virtualMap.registerMetrics(getGlobalMetrics());
        return virtualMap;
    }
}
