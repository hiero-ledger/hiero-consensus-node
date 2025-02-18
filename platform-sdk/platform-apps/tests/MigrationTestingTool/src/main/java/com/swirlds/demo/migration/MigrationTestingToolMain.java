/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.demo.migration;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.FAKE_MERKLE_STATE_LIFECYCLES;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.platform.NodeId;
import com.swirlds.fcqueue.FCQueueStatistics;
import com.swirlds.logging.legacy.payload.ApplicationFinishedPayload;
import com.swirlds.merkle.map.MerkleMapMetrics;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.StateLifecycles;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    /** create at most this many transactions in preEvent, even if more is needed to meet target rate */
    private final int transPerEventMax = 2048;
    /** transactions in each Event */
    private final int transPerSecToCreate = 100;

    private double toCreate = 0;
    private long lastEventTime = System.nanoTime();

    public static final int SOFTWARE_VERSION = 7;
    public static final BasicSoftwareVersion PREVIOUS_SOFTWARE_VERSION = new BasicSoftwareVersion(SOFTWARE_VERSION - 1);
    private final BasicSoftwareVersion softwareVersion = new BasicSoftwareVersion(SOFTWARE_VERSION);

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final Platform platform, final NodeId selfId) {
        this.platform = platform;

        final String[] parameters = ParameterProvider.getInstance().getParameters();
        logger.info(STARTUP.getMarker(), "Parsing arguments {}", (Object) parameters);
        seed = Long.parseLong(parameters[0]) + selfId.id();
        maximumTransactionsPerNode = Integer.parseInt(parameters[1]);

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
                        generateEvents();
                        Thread.sleep(1_000);
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

    private void generateEvents() {
        final long now = System.nanoTime();
        final double tps = (double) transPerSecToCreate
                / (double) platform.getRoster().rosterEntries().size();
        int numCreated = 0;

        if (transPerSecToCreate > -1) { // if not unlimited (-1 means unlimited)
            toCreate += ((double) now - lastEventTime) * NANOSECONDS_TO_SECONDS * tps;
        }

        lastEventTime = now;
        try {
            while (transactionsCreated < maximumTransactionsPerNode) {
                if (transPerSecToCreate > -1 && toCreate < 1) {
                    break; // don't create too many transactions per second
                }

                if (transPerEventMax > -1 && numCreated >= transPerEventMax) {
                    break; // don't create too many transactions per event
                }

                final byte[] transactionData = generator.generateTransaction();

                while (!platform.createTransaction(transactionData)) {
                    Thread.sleep(1_000);
                }

                transactionsCreated++;

                numCreated++;
                toCreate--;

                throttleTransactionCreation();
            }
        } catch (final SignatureException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void throttleTransactionCreation() throws InterruptedException {
        Thread.sleep(50);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MigrationTestingToolState newStateRoot() {
        final MigrationTestingToolState state = new MigrationTestingToolState();
        FAKE_MERKLE_STATE_LIFECYCLES.initStates(state);
        return state;
    }

    @Override
    public StateLifecycles<MigrationTestingToolState> newStateLifecycles() {
        return new MigrationTestToolStateLifecycles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BasicSoftwareVersion getSoftwareVersion() {
        return softwareVersion;
    }

    @Override
    @NonNull
    public Bytes encodeSystemTransaction(final @NonNull StateSignatureTransaction transaction) {
        return StateSignatureTransaction.PROTOBUF.toBytes(transaction);
    }
}
