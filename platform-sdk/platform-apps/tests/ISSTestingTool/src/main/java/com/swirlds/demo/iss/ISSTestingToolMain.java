// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.state.notifications.IssListener;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
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
public class ISSTestingToolMain implements SwirldMain<ISSTestingToolState> {

    private static final Logger logger = LogManager.getLogger(ISSTestingToolMain.class);

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering ISSTestingToolState with ConstructableRegistry");
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(new ClassConstructorPair(ISSTestingToolState.class, () -> {
                ISSTestingToolState issTestingToolState = new ISSTestingToolState();
                return issTestingToolState;
            }));
            registerMerkleStateRootClassIds();
            logger.info(STARTUP.getMarker(), "ISSTestingToolState is registered with ConstructableRegistry");
        } catch (ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register ISSTestingToolState", e);
            throw new RuntimeException(e);
        }
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

        new TransactionGenerator(new Random(), platform, testingToolConfig.transactionsPerSecond()).start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ISSTestingToolState newStateRoot() {
        final ISSTestingToolState state = new ISSTestingToolState();
        TestingAppStateInitializer.DEFAULT.initStates(state);
        return state;
    }

    /**
     * {@inheritDoc}
     * <p>
     * FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19002
     * </p>
     */
    @Override
    public Function<VirtualMap, ISSTestingToolState> stateRootFromVirtualMap() {
        throw new UnsupportedOperationException();
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

    @Override
    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        return StateSignatureTransaction.PROTOBUF.toBytes(transaction);
    }
}
