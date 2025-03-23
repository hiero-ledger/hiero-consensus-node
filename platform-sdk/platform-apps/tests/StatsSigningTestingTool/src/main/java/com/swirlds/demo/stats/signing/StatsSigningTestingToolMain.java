// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing;
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

import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.registerMerkleStateRootClassIds;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.demo.stats.signing.algorithms.ECSecP256K1Algorithm;
import com.swirlds.demo.stats.signing.algorithms.X25519SigningAlgorithm;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Browser;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * This demo collects statistics on the running of the network and consensus systems. It writes them to the screen, and
 * also saves them to disk in a comma separated value (.csv) file. Each transaction is 100 random bytes. So
 * StatsSigningDemoState.handleTransaction doesn't actually do anything.
 */
public class StatsSigningTestingToolMain implements SwirldMain<StatsSigningTestingToolState> {
    // the first four come from the parameters in the config.txt file

    public static final byte SYSTEM_TRANSACTION_MARKER = 0;
    private static final Logger logger = LogManager.getLogger(StatsSigningTestingToolMain.class);

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering StatsSigningTestingToolState with ConstructableRegistry");
            final ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(
                    new ClassConstructorPair(StatsSigningTestingToolState.class, () -> {
                        StatsSigningTestingToolState statsSigningTestingToolState = new StatsSigningTestingToolState();
                        return statsSigningTestingToolState;
                    }));
            registerMerkleStateRootClassIds();
            logger.info(STARTUP.getMarker(), "StatsSigningTestingToolState is registered with ConstructableRegistry");
        } catch (final ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register StatsSigningTestingToolState", e);
            throw new RuntimeException(e);
        }
    }
    /**
     * the time of the last measurement of TPS
     */
    long lastTPSMeasureTime = 0;
    /**
     * number of events needed to be created (the non-integer leftover from last preEvent call
     */
    double toCreate = 0;
    /**
     * should this run with no windows?
     */
    private boolean headless = false;
    /**
     * bytes in each transaction
     */
    private int bytesPerTrans = 100;
    /**
     * create at most this many transactions in preEvent, even if more is needed to meet target rate
     */
    private int transPerEventMax = 2048;
    /**
     * transactions in each Event
     */
    private int transPerSecToCreate = 100;
    /**
     * the size of the signed transaction pool
     */
    private int signedTransPoolSize = 1024;
    /**
     * the app is run by this
     */
    private Platform platform;

    private SttTransactionPool sttTransactionPool;

    private static final BasicSoftwareVersion softwareVersion = new BasicSoftwareVersion(1);

    private final StoppableThread transactionGenerator;

    private static final SpeedometerMetric.Config TRAN_SUBMIT_TPS_SPEED_CONFIG =
            new SpeedometerMetric.Config("Debug:info", "tranSubTPS").withDescription("Transaction submitted TPS");

    private SpeedometerMetric transactionSubmitSpeedometer;

    /**
     * The number of milliseconds of the window period to measure TPS over
     */
    private int tps_measure_window_milliseconds = 200;

    /**
     * The expected TPS for the network
     */
    private double expectedTPS = 0;

    /** The constant used to calculate the window size, the higher the expected TPS, the smaller the window */
    private static final long WINDOW_CALCULATION_CONST = 125000;

    /**
     * The number of seconds to ramp up the TPS to the expected value
     */
    private static final int TPS_RAMP_UP_WINDOW_MILLISECONDS = 20_000;

    /**
     * The timestamp when the ramp up started
     */
    private long rampUpStartTimeMilliSeconds = 0;

    /**
     * This is just for debugging: it allows the app to run in Eclipse. If the config.txt exists and lists a particular
     * SwirldMain class as the one to run, then it can run in Eclipse (with the green triangle icon).
     *
     * @param args
     * 		these are not used
     */
    public static void main(final String[] args) {
        Browser.parseCommandLineArgsAndLaunch(args);
    }

    public StatsSigningTestingToolMain() {
        transactionGenerator = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setComponent("demo")
                .setThreadName("transaction-generator")
                .setMaximumRate(50)
                .setWork(this::generateTransactions)
                .build();
    }

    @Override
    public void init(final Platform platform, final NodeId id) {
        this.platform = platform;
        // parse the config.txt parameters, and allow optional _ as in 1_000_000
        final String[] parameters = ParameterProvider.getInstance().getParameters();
        headless = (parameters[0].equals("1"));
        bytesPerTrans = Integer.parseInt(parameters[3].replaceAll("_", ""));
        transPerEventMax = Integer.parseInt(parameters[4].replaceAll("_", ""));
        transPerSecToCreate = Integer.parseInt(parameters[5].replaceAll("_", ""));

        expectedTPS = transPerSecToCreate
                / (double) platform.getRoster().rosterEntries().size();

        // the higher the expected TPS, the smaller the window
        tps_measure_window_milliseconds = (int) (WINDOW_CALCULATION_CONST / expectedTPS);

        // If we have a 7th setting, treat it as the signedTransPoolSize
        if (parameters.length > 6) {
            signedTransPoolSize = Integer.parseInt(parameters[6].replaceAll("_", ""));
        }

        if (transPerEventMax == -1 && transPerSecToCreate == -1) {
            // they shouldn't both be -1, so set one of them
            transPerEventMax = 1024;
        }

        sttTransactionPool = new SttTransactionPool(
                platform.getSelfId(),
                signedTransPoolSize,
                bytesPerTrans,
                true,
                new ECSecP256K1Algorithm(),
                new X25519SigningAlgorithm());

        final Metrics metrics = platform.getContext().getMetrics();
        transactionSubmitSpeedometer = metrics.getOrCreate(TRAN_SUBMIT_TPS_SPEED_CONFIG);
    }

    @Override
    public void run() {
        final Thread shutdownHook = new ThreadConfiguration(getStaticThreadManager())
                .setDaemon(false)
                .setNodeId(platform.getSelfId())
                .setComponent("app")
                .setThreadName("demo_log_time_pulse")
                .setRunnable(() -> {
                    final Logger logger = LogManager.getLogger(getClass());
                    logger.debug(STARTUP.getMarker(), "Keepalive Event for Regression Timing");
                })
                .build();

        Runtime.getRuntime().addShutdownHook(shutdownHook);

        transactionGenerator.start();

        while (true) {
            try {
                Thread.sleep(1000);
                generateTransactions();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void generateTransactions() {
        byte[] transaction;
        final long now = System.nanoTime();
        int numCreated = 0;

        // if it's first time calling this, just set lastEventTime and return
        // so the period from app start to the first time run() is called is ignored
        // to avoid a huge burst of transactions at the start of the test
        if (lastTPSMeasureTime == 0) {
            lastTPSMeasureTime = now;
            rampUpStartTimeMilliSeconds = (long) (now / MILLISECONDS_TO_NANOSECONDS);
            logger.info(
                    STARTUP.getMarker(),
                    "First time calling generateTransactions() Expected TPS per code is {}",
                    expectedTPS);
            return;
        }

        if (transPerSecToCreate > -1) { // if not unlimited (-1 means unlimited)
            // ramp up the TPS to the expected value
            final long elapsedTime = now / MILLISECONDS_TO_NANOSECONDS - rampUpStartTimeMilliSeconds;
            double rampUpTPS = 0;
            if (elapsedTime < TPS_RAMP_UP_WINDOW_MILLISECONDS) {
                rampUpTPS = expectedTPS * elapsedTime / ((double) (TPS_RAMP_UP_WINDOW_MILLISECONDS));
            } else {
                rampUpTPS = expectedTPS;
            }

            // for every measure window, re-calculate the toCreate counter
            if (((double) now - lastTPSMeasureTime) * NANOSECONDS_TO_MICROSECONDS > tps_measure_window_milliseconds) {
                toCreate = ((double) now - lastTPSMeasureTime) * NANOSECONDS_TO_SECONDS * rampUpTPS;
                lastTPSMeasureTime = now;
            }
        }

        while (true) {
            if (transPerSecToCreate > -1 && toCreate < 1) {
                break; // don't create too many transactions per second
            }
            if (transPerEventMax > -1 && numCreated >= transPerEventMax) {
                break; // don't create too many transactions per event
            }

            // Retrieve a random signed transaction from the pool
            transaction = sttTransactionPool.transaction();

            if (!platform.createTransaction(transaction)) {
                break; // if the queue is full, the stop adding to it
            }

            numCreated++;
            toCreate--;
        }
        transactionSubmitSpeedometer.update(numCreated);
        // toCreate will now represent any leftover transactions that we
        // failed to create this time, and will create next time
    }

    @Override
    @NonNull
    public StatsSigningTestingToolState newStateRoot() {
        final StatsSigningTestingToolState state = new StatsSigningTestingToolState();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initStates(state);
        return state;
    }

    @Override
    public ConsensusStateEventHandler<StatsSigningTestingToolState> newConsensusStateEvenHandler() {
        return new StatsSigningTestingToolConsensusStateEventHandler(() -> sttTransactionPool);
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
    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        final var bytes = new ByteArrayOutputStream();
        final var out = new WritableStreamingData(bytes);

        // Add a marker to indicate the start of a system transaction. This is used
        // to later differentiate between application transactions and system transactions.
        out.writeByte(SYSTEM_TRANSACTION_MARKER);
        try {
            StateSignatureTransaction.PROTOBUF.write(transaction, out);
            return Bytes.wrap(bytes.toByteArray());
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to encode a system transaction.", e);
        }
    }
}
