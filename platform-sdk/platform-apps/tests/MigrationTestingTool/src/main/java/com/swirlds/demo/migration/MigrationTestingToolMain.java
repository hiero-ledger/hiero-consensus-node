// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.logging.legacy.payload.ApplicationFinishedPayload;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.DefaultSwirldMain;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SignatureException;
import java.util.List;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * An application designed for testing migration from version to version.
 * <p>
 * Command line arguments: Seed(long), TransactionsPerNode(int)
 */
public class MigrationTestingToolMain extends DefaultSwirldMain<MigrationTestingToolState> {

    private static final Logger logger = LogManager.getLogger(MigrationTestingToolMain.class);

    private static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .autoDiscoverExtensions()
            .withSource(new SimpleConfigSource().withValue("merkleDb.initialCapacity", 1000000))
            .build();

    private long seed;
    private int maximumTransactionsPerNode;
    private int transactionsCreated;
    private TransactionGenerator generator;
    private Platform platform;

    /** transactions in each Event */
    private int transPerSecToCreate = 1000;

    private double toCreate = 0;
    private long lastGenerateTime = System.nanoTime();

    public static final int SOFTWARE_VERSION = 65;
    public static final SemanticVersion PREVIOUS_SOFTWARE_VERSION =
            SemanticVersion.newBuilder().major(SOFTWARE_VERSION - 1).build();
    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(SOFTWARE_VERSION).build();

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId selfId) {
        this.platform = platform;

        final String[] parameters = ParameterProvider.getInstance().getParameters();
        logger.info(STARTUP.getMarker(), "Parsing arguments {}", (Object) parameters);
        if (parameters == null || parameters.length < 2) {
            throw new IllegalArgumentException(
                    "MigrationTestingTool requires at least 2 parameters: Seed(long), MaxTransactionsPerNode(int)");
        }
        seed = Long.parseLong(parameters[0]) + selfId.id();
        maximumTransactionsPerNode = Integer.parseInt(parameters[1]);

        transPerSecToCreate = parameters.length >= 3 ? Integer.parseInt(parameters[2]) : transPerSecToCreate;

        generator = new TransactionGenerator(seed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try {
            logger.info(
                    STARTUP.getMarker(),
                    "MigrationTestingApp started handling {} transactions with seed {}",
                    maximumTransactionsPerNode,
                    seed);

            final RosterEntry selfEntry = RosterUtils.getRosterEntry(
                    platform.getRoster(), platform.getSelfId().id());

            final boolean isZeroWeight = selfEntry.weight() == 0L;
            if (!isZeroWeight) {
                while (transactionsCreated < maximumTransactionsPerNode) {
                    try {
                        createTransactions();
                        Thread.sleep(100);
                    } catch (final InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            logger.info(
                    STARTUP.getMarker(),
                    () -> new ApplicationFinishedPayload("MigrationTestingApp finished handling transactions"));
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void createTransactions() {
        final long now = System.nanoTime();
        final double tps = (double) transPerSecToCreate
                / (double) platform.getRoster().rosterEntries().size();

        toCreate += ((double) now - lastGenerateTime) * NANOSECONDS_TO_SECONDS * tps;

        lastGenerateTime = now;
        try {
            while (transactionsCreated < maximumTransactionsPerNode) {
                if (toCreate < 1) {
                    break; // don't create too many transactions per second
                }

                final byte[] transactionData = generator.generateTransaction();

                while (!getTransactionPool().submitApplicationTransaction(Bytes.wrap(transactionData))) {
                    Thread.sleep(100);
                }

                transactionsCreated++;

                toCreate--;
            }
        } catch (final SignatureException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MigrationTestingToolState newStateRoot() {
        final MigrationTestingToolState state =
                new MigrationTestingToolState(CONFIGURATION, new NoOpMetrics(), Time.getCurrent());
        TestingAppStateInitializer.initConsensusModuleStates(state, CONFIGURATION);
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Function<VirtualMap, MigrationTestingToolState> stateRootFromVirtualMap(
            @NonNull final Metrics metrics, @NonNull final Time time) {
        return virtualMap -> {
            final MigrationTestingToolState state = new MigrationTestingToolState(virtualMap, new NoOpMetrics(), time);
            TestingAppStateInitializer.initConsensusModuleStates(state, CONFIGURATION);
            return state;
        };
    }

    @Override
    public ConsensusStateEventHandler<MigrationTestingToolState> newConsensusStateEvenHandler() {
        return new MigrationTestToolConsensusStateEventHandler();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<Class<? extends Record>> getConfigDataTypes() {
        return List.of(MigrationTestingToolConfig.class);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SemanticVersion getSemanticVersion() {
        return semanticVersion;
    }
}
