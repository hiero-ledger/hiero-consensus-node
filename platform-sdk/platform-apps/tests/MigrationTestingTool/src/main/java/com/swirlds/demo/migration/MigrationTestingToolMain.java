// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.fcqueue.FCQueueStatistics;
import com.swirlds.logging.legacy.payload.ApplicationFinishedPayload;
import com.swirlds.merkle.map.MerkleMapMetrics;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * An application designed for testing migration from version to version.
 * <p>
 * Command line arguments: Seed(long), TransactionsPerNode(int)
 */
public class MigrationTestingToolMain implements SwirldMain<MigrationTestingToolState> {

    private static final Logger logger = LogManager.getLogger(MigrationTestingToolMain.class);

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering MigrationTestingToolState with ConstructableRegistry");
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(
                    new ClassConstructorPair(MigrationTestingToolState.class, MigrationTestingToolState::new));
            registerMerkleStateRootClassIds();
            logger.info(STARTUP.getMarker(), "MigrationTestingToolState is registered with ConstructableRegistry");
        } catch (ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register MigrationTestingToolState", e);
            throw new RuntimeException(e);
        }
    }

    private long seed;
    private int maximumTransactionsPerNode;
    private int transactionsCreated;
    private TransactionGenerator generator;
    private Platform platform;

    /** transactions in each Event */
    private int transPerSecToCreate = 1000;

    private double toCreate = 0;
    private long lastGenerateTime = System.nanoTime();

    public static final int SOFTWARE_VERSION = 61;
    public static final SemanticVersion PREVIOUS_SOFTWARE_VERSION =
            SemanticVersion.newBuilder().major(SOFTWARE_VERSION - 1).build();
    private final BasicSoftwareVersion softwareVersion = new BasicSoftwareVersion(SOFTWARE_VERSION);
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

        // Initialize application statistics
        initAppStats();
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

    private void initAppStats() {
        // Register Platform data structure statistics
        FCQueueStatistics.register(platform.getContext().getMetrics());
        MerkleMapMetrics.register(platform.getContext().getMetrics());
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

                while (!platform.createTransaction(transactionData)) {
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
        final MigrationTestingToolState state = new MigrationTestingToolState();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initStates(state);
        return state;
    }

    @Override
    public ConsensusStateEventHandler<MigrationTestingToolState> newConsensusStateEvenHandler() {
        return new MigrationTestToolConsensusStateEventHandler();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BasicSoftwareVersion getSoftwareVersion() {
        return softwareVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        return semanticVersion;
    }

    @Override
    @NonNull
    public Bytes encodeSystemTransaction(final @NonNull StateSignatureTransaction transaction) {
        return StateSignatureTransaction.PROTOBUF.toBytes(transaction);
    }
}
