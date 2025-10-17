// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerConstructablesForStorage;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.NoOpConsensusStateEventHandler;
import com.swirlds.platform.system.DefaultSwirldMain;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import java.util.function.Function;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;

/**
 * This demo collects statistics on the running of the network and consensus systems. It writes them to the
 * screen, and also saves them to disk in a comma separated value (.csv) file. Each transaction is 100
 * random bytes. So StatsDemoState.handleTransaction doesn't actually do anything.
 */
public class StatsDemoMain extends DefaultSwirldMain<StatsDemoState> {
    // the first four come from the parameters in the config.txt file

    static final Configuration CONFIGURATION =
            ConfigurationBuilder.create().autoDiscoverExtensions().build();

    /** bytes in each transaction */
    private int bytesPerTrans = 1;
    /** transactions in each Event */
    private int transPerSecToCreate = 100;

    /** the app is run by this */
    private Platform platform;
    /** used to make the transactions random, so they won't cheat and shrink when zipped */
    private Random random = new java.util.Random();

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    static {
        try {
            final ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(new ClassConstructorPair(StatsDemoState.class, () -> {
                final StatsDemoState statsDemoState = new StatsDemoState();
                return statsDemoState;
            }));
            registerMerkleStateRootClassIds();
            registerConstructablesForStorage(CONFIGURATION);
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    private final StoppableThread transactionGenerator;

    public StatsDemoMain() {
        transactionGenerator = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setComponent("demo")
                .setThreadName("transaction-generator")
                .setMaximumRate(50)
                .setWork(this::generateTransactions)
                .build();
    }

    /////////////////////////////////////////////////////////////////////
    /** the time of the last call to preEvent */
    long lastEventTime = System.nanoTime();
    /** number of events needed to be created (the non-integer leftover from last preEvent call */
    double toCreate = 0;

    private synchronized void generateTransactions() {
        final byte[] transaction = new byte[bytesPerTrans];
        final long now = System.nanoTime();
        final double tps =
                transPerSecToCreate / platform.getRoster().rosterEntries().size();

        if (transPerSecToCreate > -1) { // if not unlimited (-1 means unlimited)
            toCreate += ((double) now - lastEventTime) * NANOSECONDS_TO_SECONDS * tps;
        }
        lastEventTime = now;
        while (true) {
            if (transPerSecToCreate > -1 && toCreate < 1) {
                break; // don't create too many transactions per second
            }
            random.nextBytes(transaction); // random, so it's non-compressible
            if (!getTransactionPool().submitApplicationTransaction(Bytes.wrap(transaction))) {
                break; // if the queue is full, the stop adding to it
            }
            toCreate--;
        }
        // toCreate will now represent any leftover transactions that we
        // failed to create this time, and will create next time
    }

    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId id) {
        this.platform = platform;
        // parse the config.txt parameters
        final String[] parameters = ParameterProvider.getInstance().getParameters();
        bytesPerTrans = parameters.length > 0 ? Integer.parseInt(parameters[0]) : 100;
        transPerSecToCreate = parameters.length > 1 ? Integer.parseInt(parameters[1]) : 200;
    }

    @Override
    public void run() {
        transactionGenerator.start();
    }

    @NonNull
    @Override
    public StatsDemoState newStateRoot() {
        final StatsDemoState state = new StatsDemoState();
        TestingAppStateInitializer.initConsensusModuleStates(state, CONFIGURATION);
        return state;
    }

    /**
     * {@inheritDoc}
     * <p>
     * FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19004
     * </p>
     */
    @Override
    public Function<VirtualMap, StatsDemoState> stateRootFromVirtualMap(
            @NonNull final Metrics metrics, @NonNull final Time time) {
        return (virtualMap) -> {
            throw new UnsupportedOperationException();
        };
    }

    @NonNull
    @Override
    public ConsensusStateEventHandler newConsensusStateEvenHandler() {
        return NoOpConsensusStateEventHandler.NO_OP_CONSENSUS_STATE_EVENT_HANDLER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        return semanticVersion;
    }
}
