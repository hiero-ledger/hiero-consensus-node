// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;
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

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.Crypto;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.STORAGE_DIRECTORY;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.createExpectedMapName;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.serialize;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_6_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_9_6;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;
import static java.lang.System.exit;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.common.utility.StopWatch;
import com.swirlds.demo.merkle.map.FCMConfig;
import com.swirlds.demo.merkle.map.MapValueData;
import com.swirlds.demo.merkle.map.MapValueFCQ;
import com.swirlds.demo.merkle.map.internal.ExpectedMapUtils;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransactionWrapper;
import com.swirlds.demo.platform.nft.NftQueryController;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.demo.virtualmerkle.map.account.AccountVirtualMapKey;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapKey;
import com.swirlds.demo.virtualmerkle.state.VirtualMerkleStateInitializer;
import com.swirlds.demo.virtualmerkle.transaction.handler.VirtualMerkleTransactionHandler;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.fcqueue.FCQueueStatistics;
import com.swirlds.logging.legacy.payload.ApplicationFinishedPayload;
import com.swirlds.logging.legacy.payload.CreateTransactionFailedPayload;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Browser;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import com.swirlds.platform.system.state.notifications.NewSignedStateListener;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.constructable.NoArgsConstructor;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This demo tests platform features and collects statistics on the running of the network and consensus systems. It
 * writes them to the screen, and also saves them to disk in a comma separated value (.csv) file.
 * Each transaction consists of an optional sequence number and random bytes.
 */
public class PlatformTestingToolMain implements SwirldMain<PlatformTestingToolState> {

    /**
     * use this for all logging
     */
    private static final Logger logger = LogManager.getLogger(PlatformTestingToolMain.class);

    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker LOGM_STARTUP = MarkerManager.getMarker("STARTUP");
    private static final Marker LOGM_SUBMIT_DETAIL = MarkerManager.getMarker("SUBMIT_DETAIL");
    private static final Marker LOGM_DEMO_QUORUM = MarkerManager.getMarker("DEMO_QUORUM");

    private static final String FREEZE_TRANSACTION_TYPE = "freeze";

    private static final String FCM_CATEGORY = "FCM";
    private static final String VM_CATEGORY = "VM";

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering PlatformTestingToolState with ConstructableRegistry");
            ConstructableRegistry.getInstance()
                    .registerConstructable(new ClassConstructorPair(PlatformTestingToolState.class, () -> {
                        final PlatformTestingToolState ptt = new PlatformTestingToolState();
                        return ptt;
                    }));
            logger.info(
                    STARTUP.getMarker(),
                    "PlatformTestingToolState is registered with ConstructableRegistry: {}",
                    ConstructableRegistry.getInstance()
                            .getRegistry(NoArgsConstructor.class)
                            .getConstructor(PlatformTestingToolState.CLASS_ID)
                            .get()
                            .getClassId());
            registerMerkleStateRootClassIds();
        } catch (final ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register PlatformTestingToolState", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * save internal file logs and expected map to file while freezing;
     * for restart test we should set `saveExpectedMapAtFreeze` to be true, so that
     * ExpectedFCMFamily could be recovered at restart.
     * Note: it might not work with TPS higher than 5k, because the nodes might not be able to finish writing file to
     * disk before being shut down for restart.
     */
    boolean saveExpectedMapAtFreeze = false;
    /////////////////////////////////////////////////////////////////////
    Triple<byte[], PAYLOAD_TYPE, MapKey> submittedPayloadTriple = null;
    private static final int MILLIS_TO_SEC = 1000;
    int CHECK_IDLE_MILLISECONDS = 10_000;
    boolean noMoreTransaction = false;
    /** allow run thread also submit transactions */
    private boolean allowRunSubmit = false;
    /**
     * whether enable check after test run. When enableCheck is false TYPE_TEST_PAUSE and TYPE_TEST_SYNC transactions
     * are not needed in sequential test. As there is no TYPE_TEST_PAUSE before delete transactions, if one node is
     * slower than other nodes, other nodes might handle slower nodes transactions after entities are deleted.
     * This might cause performOnDeleted errors. So performOnDeleted is set to true when enableCheck is false
     */
    private boolean enableCheck = true;

    private boolean waitForSaveStateDuringFreeze = false;
    public AtomicBoolean handledExitValidation = new AtomicBoolean(false);
    /** query record */
    private boolean queryRecord = false;

    /** algorithm to decide whether submit based on statistics and goal */
    private TransactionSubmitter submitter;

    private static final Counter.Config TRANSACTION_SUBMITTED_CONFIG =
            new Counter.Config("Debug:info", "tranSub").withDescription("number of transactions submitted to platform");
    private Counter transactionSubmitted;

    private boolean checkedThisIdleInterval = false; // each long idle interval only check once
    /** the configuration used by this app */
    private final PlatformConfig config;
    /** ID number for this member */
    private NodeId selfId;
    /** the app is run by this */
    private Platform platform;
    /** the platform is active now or not */
    private volatile boolean isActive = false;

    private static final int CLIENT_AMOUNT = 2;
    AppClient[] appClient = new AppClient[CLIENT_AMOUNT];
    /** generate different payload bytes according to config */
    private PttTransactionPool pttTransactionPool;

    private SubmitConfig submitConfig;
    private PayloadConfig payloadConfig;
    private SuperConfig currentConfig;
    /** total FCM transactions, not including previous runs before restart */
    private long totalFCMTransactions;

    private long prevFCMCreateAmount;
    private long prevFCMUpdateAmount;
    private long prevFCMTransferAmount;
    private long prevFCMDeleteAmount;

    private long prevVMCreateAmount;
    private long prevVMUpdateAmount;
    private long prevVMDeleteAmount;
    private long prevContractCreateAmount;
    private long prevContractExecutionAmount;

    private static final String PTT_COMPONENT = "PTT";
    private static final String ENTER_VALIDATION_THREAD_NAME = "enter-validator";
    private static final String EXIT_VALIDATION_THREAD_NAME = "exit-validator";

    private static final SpeedometerMetric.Config FCM_CREATE_SPEED_CONFIG =
            new SpeedometerMetric.Config(FCM_CATEGORY, "fcmCreate").withDescription("FCM Creation TPS");
    private SpeedometerMetric fcmCreateSpeed;
    private static final SpeedometerMetric.Config FCM_UPDATE_SPEED_CONFIG =
            new SpeedometerMetric.Config(FCM_CATEGORY, "fcmUpdate").withDescription("FCM Update TPS");
    private SpeedometerMetric fcmUpdateSpeed;
    private static final SpeedometerMetric.Config FCM_TRANSFER_SPEED_CONFIG =
            new SpeedometerMetric.Config(FCM_CATEGORY, "fcmTransfer").withDescription("FCM Transfer TPS");
    private SpeedometerMetric fcmTransferSpeed;
    private static final SpeedometerMetric.Config FCM_DELETE_SPEED_CONFIG =
            new SpeedometerMetric.Config(FCM_CATEGORY, "fcmDelete").withDescription("FCM Delete TPS");
    private SpeedometerMetric fcmDeleteSpeed;

    private static final SpeedometerMetric.Config VM_CREATE_SPEED_CONFIG =
            new SpeedometerMetric.Config(VM_CATEGORY, "vmCreate").withDescription("VM Creation TPS");
    private SpeedometerMetric vmCreateSpeed;
    private static final SpeedometerMetric.Config VM_UPDATE_SPEED_CONFIG =
            new SpeedometerMetric.Config(VM_CATEGORY, "vmUpdate").withDescription("VM Update TPS");
    private SpeedometerMetric vmUpdateSpeed;
    private static final SpeedometerMetric.Config VM_DELETE_SPEED_CONFIG =
            new SpeedometerMetric.Config(VM_CATEGORY, "vmDelete").withDescription("VM Deletion TPS");
    private SpeedometerMetric vmDeleteSpeed;
    private static final SpeedometerMetric.Config VM_CONTRACT_CREATE_SPEED_CONFIG =
            new SpeedometerMetric.Config(VM_CATEGORY, "vmContractCreate").withDescription("VM Contract Creation TPS");
    private SpeedometerMetric vmContractCreateSpeed;
    private static final SpeedometerMetric.Config VM_CONTRACT_EXECUTION_SPEED_CONFIG =
            new SpeedometerMetric.Config(VM_CATEGORY, "vmContractExecute").withDescription("VM Contract Execution TPS");
    private SpeedometerMetric vmContractExecutionSpeed;

    private static final SpeedometerMetric.Config TRAN_SUBMIT_TPS_SPEED_CONFIG =
            new SpeedometerMetric.Config("Debug:info", "tranSubTPS").withDescription("Transaction submitted TPS");
    private SpeedometerMetric transactionSubmitSpeedometer;

    private FCMQueryController queryController;
    private NftQueryController nftQueryController;

    /**
     * default half-life for statistics
     */
    private static final double DEFAULT_HALF_LIFE = 10;

    /**
     * avg time taken to query a leaf in the latest signed state (in microseconds)
     */
    private static final RunningAverageMetric.Config QUERY_LEAF_TIME_COST_MICRO_SEC_CONFIG =
            new RunningAverageMetric.Config("Query", "queryLeafTimeCostMicroSec")
                    .withDescription("avg time taken to query a leaf in the latest signed state (in microseconds)")
                    .withHalfLife(DEFAULT_HALF_LIFE);

    private RunningAverageMetric queryLeafTimeCostMicroSec;

    /**
     * how many queries have been answered per second
     */
    private static final SpeedometerMetric.Config QUERIES_ANSWERED_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    "Query", "queriesAnsweredPerSecond")
            .withDescription("number of queries have been answered per second")
            .withFormat(FORMAT_9_6);

    private SpeedometerMetric queriesAnsweredPerSecond;

    private static final RunningAverageMetric.Config EXPECTED_INVALID_SIG_RATIO_CONFIG =
            new RunningAverageMetric.Config(FCM_CATEGORY, "expectedInvalidSigRatio")
                    .withDescription("Expected invalid signature ratio")
                    .withFormat(FORMAT_6_2);
    private RunningAverageMetric expectedInvalidSigRatio;

    /**
     * Is used for determining whether it's time to check account balances
     */
    private Instant previousTimestamp = null;
    /** defines how many queries should be sent in each second for querying a leaf in the latest signed state */
    private long queriesSentPerSec = -1;

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    final PlatformTestingToolConsensusStateEventHandler consensusStateEventHandler;

    public PlatformTestingToolMain() {
        // the config needs to be loaded before the init() method
        config = PlatformConfig.getDefault();
        consensusStateEventHandler = new PlatformTestingToolConsensusStateEventHandler(new PlatformStateFacade());
    }

    /**
     * This is just for debugging: it allows the app to run in Eclipse. If the config.txt exists and lists a
     * particular SwirldMain class as the one to run, then it can run in Eclipse (with the green triangle icon).
     *
     * @param args
     * 		these are not used
     */
    public static void main(final String[] args) {
        Browser.parseCommandLineArgsAndLaunch(args);
    }

    private void printJVMParameters() {
        final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        final List<String> jvmArgs = runtimeMXBean.getInputArguments();
        if (jvmArgs.size() == 0) {
            logger.error(EXCEPTION.getMarker(), "No JVM ARGS!");
        }
        for (final String arg : jvmArgs) {
            logger.info(LOGM_STARTUP, "JVM arg: {}", arg);
        }
    }

    private void sendFreezeTransaction() {
        // decide the freeze time
        final Instant startTime = Instant.now().plus(1, ChronoUnit.MINUTES);

        final byte[] freezeBytes = pttTransactionPool.createFreezeTranByte(startTime);

        if (!submitter.sendFreezeTran(platform, freezeBytes)) {
            logger.warn(DEMO_INFO.getMarker(), new CreateTransactionFailedPayload(FREEZE_TRANSACTION_TYPE));
        }
    }

    /**
     * return true if can continue to submit, otherwise return false
     */
    private synchronized boolean subRoutine() {

        if (submittedPayloadTriple == null) { // if no pending payload
            submittedPayloadTriple = pttTransactionPool.transaction();
        }

        if (submittedPayloadTriple != null) {
            logger.info(
                    LOGM_SUBMIT_DETAIL,
                    "is about to submit a {} transaction for {}",
                    submittedPayloadTriple.middle(),
                    submittedPayloadTriple.right());
            // if the platform is not active, we don't submit transaction
            if (!isActive) {
                logger.info(LOGM_SUBMIT_DETAIL, "will not submit the transaction because isActive is false");
                return false;
            }
            final boolean success = submitter.trySubmit(
                    platform, Pair.of(submittedPayloadTriple.left(), submittedPayloadTriple.middle()));
            if (!success) { // if failed keep bytes payload try next time
                try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                        UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
                    Thread.sleep(50);
                    final PlatformTestingToolState state = wrapper.get();
                    ExpectedMapUtils.modifySubmitStatus(state, false, isActive, submittedPayloadTriple, payloadConfig);
                } catch (final InterruptedException e) {
                    logger.error(EXCEPTION.getMarker(), "", e);
                }
                return false;
            } else {
                if (checkedThisIdleInterval) { // clear the flag if we can submit again
                    checkedThisIdleInterval = false;
                }
                transactionSubmitted.increment();
                transactionSubmitSpeedometer.update(1);
                try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                        UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
                    final PlatformTestingToolState state = wrapper.get();
                    ExpectedMapUtils.modifySubmitStatus(state, true, isActive, submittedPayloadTriple, payloadConfig);
                }

                submittedPayloadTriple = null; // release the payload
            }
        } else {
            // empty means no more transaction
            logger.info(LOGM_DEMO_INFO, "Stop generating transactions ");
            submitter.sendTransaction(
                    platform, pttTransactionPool.createControlTranBytes(ControlType.ENTER_VALIDATION));
            logger.info(LOGM_DEMO_INFO, "node {} sent ENTER_VALIDATION Message", platform.getSelfId());
            noMoreTransaction = true;
            return false;
        }
        return true;
    }

    private void initAppStat() {
        // Add virtual merkle stats
        final Metrics metrics = platform.getContext().getMetrics();
        vmCreateSpeed = metrics.getOrCreate(VM_CREATE_SPEED_CONFIG);
        vmUpdateSpeed = metrics.getOrCreate(VM_UPDATE_SPEED_CONFIG);
        vmDeleteSpeed = metrics.getOrCreate(VM_DELETE_SPEED_CONFIG);
        vmContractCreateSpeed = metrics.getOrCreate(VM_CONTRACT_CREATE_SPEED_CONFIG);
        vmContractExecutionSpeed = metrics.getOrCreate(VM_CONTRACT_EXECUTION_SPEED_CONFIG);

        // Add FCM speedometer
        fcmCreateSpeed = metrics.getOrCreate(FCM_CREATE_SPEED_CONFIG);
        fcmUpdateSpeed = metrics.getOrCreate(FCM_UPDATE_SPEED_CONFIG);
        fcmTransferSpeed = metrics.getOrCreate(FCM_TRANSFER_SPEED_CONFIG);
        fcmDeleteSpeed = metrics.getOrCreate(FCM_DELETE_SPEED_CONFIG);

        // Add some global information for debugging
        transactionSubmitted = metrics.getOrCreate(TRANSACTION_SUBMITTED_CONFIG);
        transactionSubmitSpeedometer = metrics.getOrCreate(TRAN_SUBMIT_TPS_SPEED_CONFIG);

        // add stats for time taken to query a leaf
        queryLeafTimeCostMicroSec = metrics.getOrCreate(QUERY_LEAF_TIME_COST_MICRO_SEC_CONFIG);

        queriesAnsweredPerSecond = metrics.getOrCreate(QUERIES_ANSWERED_PER_SECOND_CONFIG);

        expectedInvalidSigRatio = metrics.getOrCreate(EXPECTED_INVALID_SIG_RATIO_CONFIG);

        // Register Platform data structure statistics
        FCQueueStatistics.register(metrics);

        // Register PTT statistics
        PlatformTestingToolConsensusStateEventHandler.initStatistics(platform);

        final int SAMPLING_PERIOD = 5000; /* millisecond */
        final Timer statTimer = new Timer("stat timer" + selfId, true);
        statTimer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                                UnsafeMutablePTTStateAccessor.getInstance()
                                        .getUnsafeMutableState(platform.getSelfId())) {
                            final PlatformTestingToolState state = wrapper.get();
                            if (state != null) {
                                getCurrentTransactionStat(state);
                            }
                        }
                    }
                },
                0,
                SAMPLING_PERIOD);
    }

    private long summation(final List<TransactionCounter> counters, final ValueExtractor valueExtractor) {
        long total = 0;
        if (counters != null) {
            for (final TransactionCounter counter : counters) {
                total += valueExtractor.getValue(counter);
            }
        }
        return total;
    }

    private void getCurrentTransactionStat(final PlatformTestingToolState state) {
        final long fcmCreateAmount = summation(state.getTransactionCounter(), (o) -> o.fcmCreateAmount);
        final long fcmUpdateAmount = summation(state.getTransactionCounter(), (o) -> o.fcmUpdateAmount);
        final long fcmTransferAmount = summation(state.getTransactionCounter(), (o) -> o.fcmTransferAmount);
        final long fcmDeleteAmount = summation(state.getTransactionCounter(), (o) -> o.fcmDeleteAmount);

        final long vmCreateAmount = summation(state.getTransactionCounter(), (o) -> o.vmCreateAmount);
        final long vmDeleteAmount = summation(state.getTransactionCounter(), (o) -> o.vmDeleteAmount);
        final long vmUpdateAmount = summation(state.getTransactionCounter(), (o) -> o.vmUpdateAmount);
        final long vmContractCreateAmount = summation(state.getTransactionCounter(), (o) -> o.vmContractCreateAmount);
        final long vmContractExecutionAmount =
                summation(state.getTransactionCounter(), (o) -> o.vmContractExecutionAmount);

        totalFCMTransactions = fcmCreateAmount + fcmUpdateAmount + fcmTransferAmount + fcmDeleteAmount;

        fcmCreateSpeed.update(fcmCreateAmount - prevFCMCreateAmount);
        fcmUpdateSpeed.update(fcmUpdateAmount - prevFCMUpdateAmount);
        fcmTransferSpeed.update(fcmTransferAmount - prevFCMTransferAmount);
        fcmDeleteSpeed.update(fcmDeleteAmount - prevFCMDeleteAmount);

        vmCreateSpeed.update(vmCreateAmount - prevVMCreateAmount);
        vmUpdateSpeed.update(vmUpdateAmount - prevVMUpdateAmount);
        vmDeleteSpeed.update(vmDeleteAmount - prevVMDeleteAmount);
        vmContractCreateSpeed.update(vmContractCreateAmount - prevContractCreateAmount);
        vmContractExecutionSpeed.update(vmContractExecutionAmount - prevContractExecutionAmount);

        prevFCMCreateAmount = fcmCreateAmount;
        prevFCMUpdateAmount = fcmUpdateAmount;
        prevFCMTransferAmount = fcmTransferAmount;
        prevFCMDeleteAmount = fcmDeleteAmount;

        prevVMCreateAmount = vmCreateAmount;
        prevVMUpdateAmount = vmUpdateAmount;
        prevVMDeleteAmount = vmDeleteAmount;
        prevContractCreateAmount = vmContractCreateAmount;
        prevContractExecutionAmount = vmContractExecutionAmount;

        if (PlatformTestingToolState.totalTransactionSignatureCount.get() > 0) {
            expectedInvalidSigRatio.update((double) PlatformTestingToolState.expectedInvalidSignatureCount.get()
                    / PlatformTestingToolState.totalTransactionSignatureCount.get());
        }
    }

    // use static function so it can be also used by PlatformTestingState
    private static InputStream resolveConfigFile(final String fileName) throws IOException {
        final ClassLoader classLoader = PlatformTestingToolMain.class.getClassLoader();
        InputStream stream = classLoader.getResourceAsStream(fileName);

        if (stream == null) {
            final File inputFile = new File(fileName);

            if (!inputFile.exists() || !inputFile.isFile()) {
                throw new FileNotFoundException(fileName);
            }

            stream = new FileInputStream(inputFile);
        }

        return stream;
    }

    /**
     * A static function used to load PayloadCfgSimple from json configuration file
     *
     * @param jsonFileName
     * 		Top level json configuration for PTT App
     */
    public static PayloadCfgSimple getPayloadCfgSimple(final String jsonFileName) {
        try {
            if (jsonFileName != null && jsonFileName.length() > 0) {
                final ObjectMapper objectMapper =
                        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
                final SuperConfig currentConfig =
                        objectMapper.readValue(resolveConfigFile(jsonFileName), SuperConfig.class);
                return currentConfig.getPayloadConfig();
            } else {
                return null;
            }
        } catch (final IOException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "ERROR in getting PayloadCfgSimple, please check JSON file syntax. Stack Trace =",
                    e);
            exit(-2);
        }
        return null;
    }

    @Override
    public void init(final Platform platform, final NodeId id) {
        this.platform = platform;
        selfId = id;

        platform.getNotificationEngine().register(PlatformStatusChangeListener.class, this::platformStatusChange);
        registerReconnectCompleteListener();

        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
            final PlatformTestingToolState state = wrapper.get();

            consensusStateEventHandler.initControlStructures(this::handleMessageQuorum);

            // FUTURE WORK implement mirrorNode
            final String myName =
                    RosterUtils.formatNodeName(platform.getSelfId().id());

            // Parameters[0]: JSON file for test config
            String jsonFileName = null;
            final String[] parameters = ParameterProvider.getInstance().getParameters();
            if (parameters != null && parameters.length > 0) {
                jsonFileName = parameters[0];
            }

            // Parameters[1]: JSON file for app client (parsed below), optional

            final ProgressCfg progressCfg = new ProgressCfg();

            if (jsonFileName != null && jsonFileName.length() > 0) {
                final ObjectMapper objectMapper =
                        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);

                try {
                    printJVMParameters();
                    logger.info(LOGM_STARTUP, "Parsing JSON {}", jsonFileName);
                    currentConfig = objectMapper.readValue(resolveConfigFile(jsonFileName), SuperConfig.class);
                    // load sequentials configs,
                    // and calculate expected amount of each EntityType to be created by each node,
                    // which would be used when setting initialCapacity of collections in ExpectedFCMFamily
                    final FCMConfig fcmConfig = currentConfig.getFcmConfig();
                    fcmConfig.loadSequentials();
                    fcmConfig.loadVirtualMerkleSequentials(currentConfig.getVirtualMerkleConfig());

                    final PayloadCfgSimple pConfig = currentConfig.getPayloadConfig();
                    final PayloadConfig payloadConfig = buildPayloadConfig(pConfig);

                    this.enableCheck = pConfig.isEnableCheck();
                    // if enableCheck is false, TYPE_TEST_PAUSE and TYPE_TEST_SYNC transactions don't work. So, if one
                    // node submits/handles transactions slower than other nodes, it might handle transactions on
                    // deleted entities. As these errors are allowed in such scenario, performOnDeleted is set to true.
                    if (!this.enableCheck && !pConfig.isPerformOnDeleted()) {
                        payloadConfig.setPerformOnDeleted(true);
                    }

                    initBasedOnPayloadCfgSimple(pConfig);
                    this.payloadConfig = payloadConfig;

                    SyntheticBottleneckConfig.setActiveConfig(currentConfig.getSyntheticBottleneckConfig());

                    progressCfg.setProgressMarker(pConfig.getProgressMarker());

                    payloadConfig.display();
                    state.setPayloadConfig(currentConfig.getFcmConfig(), platform.getRoster());
                    if (currentConfig.getFcmConfig().getFcmQueryConfig() != null) {
                        this.queryController = new FCMQueryController(
                                currentConfig.getFcmConfig().getFcmQueryConfig(), platform);
                    }

                    this.nftQueryController = new NftQueryController(currentConfig.getNftConfig(), platform);

                    submitConfig = currentConfig.getSubmitConfig();

                    submitter = new TransactionSubmitter(submitConfig, consensusStateEventHandler.getControlQuorum());

                    if (currentConfig.getFcmConfig() != null) {
                        state.initChildren();
                        final VirtualMerkleConfig virtualMerkleConfig = currentConfig.getVirtualMerkleConfig();
                        if (virtualMerkleConfig != null) {
                            final Pair<Long, Long> entitiesFirstIds = extractFirstIdForEntitiesFromSavedState(platform);
                            virtualMerkleConfig.setFirstAccountId(entitiesFirstIds.key());
                            virtualMerkleConfig.setFirstSmartContractId(entitiesFirstIds.value());
                            VirtualMerkleStateInitializer.initStateChildren(platform, selfId.id(), virtualMerkleConfig);
                        }
                        final Metrics metrics = platform.getContext().getMetrics();
                        if (state.getVirtualMap() != null) {
                            state.getVirtualMap().registerMetrics(metrics);
                        }
                        if (state.getVirtualMapForSmartContracts() != null) {
                            state.getVirtualMapForSmartContracts().registerMetrics(metrics);
                        }
                        if (state.getVirtualMapForSmartContractsByteCode() != null) {
                            state.getVirtualMapForSmartContractsByteCode().registerMetrics(metrics);
                        }
                        initializeProgressCfgForSequentialTest(progressCfg, currentConfig);
                    }

                    // The instantiation of the transaction pool needs to be done after the
                    // virtualMerkleConfig receives the first ids to be used by the entities
                    // through calls to the setFirstAccountId and setFirstSmartContractId methods.
                    pttTransactionPool = new PttTransactionPool(
                            platform,
                            platform.getSelfId().id(),
                            payloadConfig,
                            myName,
                            currentConfig.getFcmConfig(),
                            currentConfig.getVirtualMerkleConfig(),
                            currentConfig.getFreezeConfig(),
                            currentConfig.getTransactionPoolConfig(),
                            submitter,
                            state.getStateExpectedMap(),
                            currentConfig.getIssConfig());

                    if (submitConfig != null) {
                        this.allowRunSubmit = submitConfig.isAllowRunSubmit();
                    }

                    if (currentConfig.getQueryConfig() != null) {
                        queriesSentPerSec = currentConfig.getQueryConfig().getQueriesSentPerSec();
                        logger.info(LOGM_DEMO_INFO, "queriesSentPerSec: {}", queriesSentPerSec);
                    }

                    initializeAppClient(parameters, objectMapper);
                } catch (final NullPointerException | IOException e) {
                    logger.error(
                            EXCEPTION.getMarker(),
                            "ERROR in parsing JSON configuration file, please check JSON file syntax. Stack Trace =",
                            e);
                    exit(-2);
                }
            }

            state.setProgressCfg(progressCfg);
        } catch (final NullPointerException e) {
            logger.error(EXCEPTION.getMarker(), "ERROR in parsing JSON configuration file. ", e);
            exit(-2);
        }

        initAppStat();

        if (waitForSaveStateDuringFreeze) {
            registerFinishAfterSaveStateDuringFreezeListener();
        }

        platform.getNotificationEngine().register(NewSignedStateListener.class, notification -> {
            if (timeToCheckBalances(notification.getConsensusTimestamp())) {
                checkBalances(notification.getState());
            }
        });
    }

    private PayloadConfig buildPayloadConfig(final PayloadCfgSimple pConfig) {
        return PayloadConfig.builder()
                .setAppendSig(pConfig.isAppendSig())
                .setInsertSeq(pConfig.isInsertSeq())
                .setVariedSize(pConfig.isVariedSize())
                .setPayloadByteSize(pConfig.getPayloadByteSize())
                .setMaxByteSize(pConfig.getMaxByteSize())
                .setType(pConfig.getType())
                .setDistribution(pConfig.getDistribution())
                .setInvalidSigRatio(pConfig.getInvalidSigRatio())
                .setCreateOnExistingEntities(pConfig.isCreateOnExistingEntities())
                .setPerformOnDeleted(pConfig.isPerformOnDeleted())
                .setPerformOnNonExistingEntities(pConfig.isPerformOnNonExistingEntities())
                .setOperateEntitiesOfSameNode(pConfig.isOperateEntitiesOfSameNode())
                .setRatioOfFCMTransaction(pConfig.getRatioOfFCMTransaction())
                .build();
    }

    private void initBasedOnPayloadCfgSimple(final PayloadCfgSimple pConfig) {
        this.waitForSaveStateDuringFreeze = pConfig.isWaitForSaveStateDuringFreeze();
        this.saveExpectedMapAtFreeze = pConfig.isSaveExpectedMapAtFreeze();
        this.queryRecord = pConfig.isQueryRecord();
    }

    private void initializeAppClient(final String[] pars, final ObjectMapper objectMapper) throws IOException {
        if ((pars == null) || (pars.length < 2) || !selfId.equals(NodeId.of(0L))) {
            return;
        }

        final String jsonFileName = pars[1];
        if (jsonFileName.trim().isBlank()) {
            return;
        }

        logger.info(LOGM_DEMO_INFO, "Parsing JSON for client: {}", jsonFileName);
        final SuperConfig clientConfig = objectMapper.readValue(new File(jsonFileName), SuperConfig.class);
        final String selfName = RosterUtils.formatNodeName(selfId.id());
        for (int k = 0; k < CLIENT_AMOUNT; k++) {
            appClient[k] =
                    new AppClient(this.platform, this.selfId, clientConfig, selfName, consensusStateEventHandler);
            appClient[k].start();
        }
    }

    private void initializeProgressCfgForSequentialTest(
            final ProgressCfg progressCfg, final SuperConfig currentConfig) {
        if (!currentConfig.getFcmConfig().isSequentialTest()) {
            return;
        }

        progressCfg.setExpectedFCMCreateAmount(
                currentConfig.getFcmConfig().getTranAmountByType(PAYLOAD_TYPE.TYPE_FCM_CREATE)
                        * platform.getRoster().rosterEntries().size());
        progressCfg.setExpectedFCMTransferAmount(
                currentConfig.getFcmConfig().getTranAmountByType(PAYLOAD_TYPE.TYPE_FCM_TRANSFER)
                        * platform.getRoster().rosterEntries().size());
        progressCfg.setExpectedFCMUpdateAmount(
                currentConfig.getFcmConfig().getTranAmountByType(PAYLOAD_TYPE.TYPE_FCM_UPDATE)
                        * platform.getRoster().rosterEntries().size());
        progressCfg.setExpectedFCMDeleteAmount(
                currentConfig.getFcmConfig().getTranAmountByType(PAYLOAD_TYPE.TYPE_FCM_DELETE)
                        * platform.getRoster().rosterEntries().size());
        progressCfg.setExpectedFCMAssortedAmount(
                currentConfig.getFcmConfig().getTranAmountByType(PAYLOAD_TYPE.TYPE_FCM_ASSORTED)
                        * platform.getRoster().rosterEntries().size());
    }

    @Override
    public void run() {

        if (queryRecord) {
            // query record periodically
            final Thread queryRecordThread = new Thread(this::queryRecord);
            queryRecordThread.setName("queryRecord_node" + selfId);
            queryRecordThread.start();
        }

        if (queriesSentPerSec > 0) {
            queryInState();
        }

        if (queryController != null) {
            queryController.launch();
        }

        nftQueryController.launch();

        // reset interval timestamp before start generating transactions
        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
            final PlatformTestingToolState state = wrapper.get();
            state.resetLastFileTranFinishTimeStamp();
        }

        if (allowRunSubmit) {

            // if single mode only node 0 can submit transactions
            // if not single mode anyone can submit transactions
            if (!submitConfig.isSingleNodeSubmit() || selfId.equals(NodeId.of(0L))) {

                if (submitConfig.isSubmitInTurn()) {
                    // Delay the start of transactions by interval multiply by node id
                    // for example, node 0 starts after 10 seconds, node 1 starts after 20 seconds
                    // node 2 starts after 30 seconds
                    try {
                        Thread.sleep(submitConfig.getInTurnIntervalSecond() * MILLIS_TO_SEC * selfId.id());
                    } catch (final InterruptedException e) {
                        // Suppress
                    }
                }
                logger.info(LOGM_DEMO_INFO, "Node {} starts transactions ........", selfId);
                while (!noMoreTransaction) {
                    // if the platform is not Active, wait until it become Active
                    while (!isActive) {
                        try {
                            Thread.sleep(50);
                        } catch (final InterruptedException e) {
                            logger.error(EXCEPTION.getMarker(), "", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                    // initialize and submit transaction
                    subRoutine();
                    if ((noMoreTransaction && !enableCheck && !waitForSaveStateDuringFreeze)) {
                        logger.info(LOGM_DEMO_INFO, () -> new ApplicationFinishedPayload("Transactions finished"));
                    }
                } // while

                logger.info(LOGM_DEMO_INFO, "Node {} finished generating all transactions.", selfId);

                // This run function is used to generate transactions for PTT App.
                // As mentioned in java doc of {@link com.swirlds.common.system.SwirldsMain#run}
                // run() method should run until the user quits the swirld.

                // Therefore, the function must wait here and keep this thread alive,
                // so the JVM and all other non-daemon threads can continue to run, to handle events and transactions,
                // etc
                // PTT App would quit itself when all transactions being handled, as shown in
                // {@link #logSuccessMessageAndFinishTest()}

                while (true) {
                    abortAndThrowIfInterrupted(() -> Thread.sleep(60000), "PTT main thread interrupted");
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformTestingToolState newStateRoot() {
        final PlatformTestingToolState state = new PlatformTestingToolState();
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
    public Function<VirtualMap, PlatformTestingToolState> stateRootFromVirtualMap() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusStateEventHandler<PlatformTestingToolState> newConsensusStateEvenHandler() {
        return consensusStateEventHandler;
    }

    private void platformStatusChange(final PlatformStatusChangeNotification notification) {
        final PlatformStatus newStatus = notification.getNewStatus();
        // set isActive
        isActive = newStatus == PlatformStatus.ACTIVE;

        if (newStatus == PlatformStatus.FREEZING) {
            logger.trace(LOGM_DEMO_INFO, "ENTERING FREEZING!");
            logger.trace(
                    LOGM_DEMO_INFO,
                    "total submitted transactions: {}, FCM Transactions: {}",
                    transactionSubmitted,
                    totalFCMTransactions);
        }

        logger.info(LOGM_STARTUP, "Platform Status Change {} ", newStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        return semanticVersion;
    }

    private boolean timeToCheckBalances(final Instant consensusTimestamp) {
        final int checkPeriodSec = 20;
        if (previousTimestamp != null
                && consensusTimestamp.getEpochSecond() / checkPeriodSec
                        != previousTimestamp.getEpochSecond() / checkPeriodSec) {
            previousTimestamp = consensusTimestamp;
            return true;
        }
        previousTimestamp = consensusTimestamp;
        return false;
    }

    /**
     * Iterate each value in accountMap for checking balances
     *
     * @param state
     */
    private void checkBalances(final PlatformTestingToolState state) {
        long totalBalance = 0;
        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> accountMap =
                state.getStateMap().getAccountFCQMap();
        for (final Map.Entry<MapKey, MapValueFCQ<TransactionRecord>> item : accountMap.entrySet()) {
            final MapValueFCQ<TransactionRecord> currMv = item.getValue();
            totalBalance += currMv.getBalance().getValue();
        }
    }

    /**
     * query records in accountMap
     */
    private void queryRecord() {
        final Random random = new Random();
        try {
            Thread.sleep(3000);
            while (true) {
                try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                        UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
                    final PlatformTestingToolState state = wrapper.get();
                    if (state != null) {
                        final int randomId = random.nextInt(
                                platform.getRoster().rosterEntries().size());
                        final TransactionCounter txCounter =
                                state.getTransactionCounter().get(randomId);
                        final int accountsCount = (int) txCounter.fcmFCQCreateAmount;
                        if (accountsCount > 0) {
                            final int randomAccountNum = random.nextInt(accountsCount);
                            final MapKey key = new MapKey(randomId, randomId, randomAccountNum);

                            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> accountMap =
                                    state.getStateMap().getAccountFCQMap();
                            final FCQueue<TransactionRecord> records =
                                    accountMap.get(key).getRecords();
                            if (!records.isEmpty()) {
                                final int randomIndex = random.nextInt(records.size());
                                for (final TransactionRecord record : records) {
                                    if (record.getIndex() == randomIndex) {
                                        record.getBalance();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                Thread.sleep(3000);
            }
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "error(ERROR queryRecord", e);
        }
    }

    /**
     * once reconnect is completed, rebuild ExpectedMap based on actual MerkleMaps
     */
    private void registerReconnectCompleteListener() {
        platform.getNotificationEngine().register(ReconnectCompleteListener.class, (notification) -> {
            logger.info(
                    LOGM_DEMO_INFO,
                    "Notification Received: Reconnect Finished."
                            + " consensusTimestamp: {}, roundNumber: {}, sequence: {}",
                    notification::getConsensusTimestamp,
                    notification::getRoundNumber,
                    notification::getSequence);
            ExpectedMapUtils.buildExpectedMapAfterReconnect(notification, platform);
            rebuildExpirationQueue(platform);

            try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                    UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
                final PlatformTestingToolState state = wrapper.get();
                consensusStateEventHandler.initControlStructures(this::handleMessageQuorum);
                SyntheticBottleneckConfig.getActiveConfig()
                        .registerReconnect(platform.getSelfId().id());
            }
        });
    }

    /**
     * Rebuild ExpirationQueue after reconnect/restart complete notification is received.
     *
     * @param platform
     */
    private void rebuildExpirationQueue(final Platform platform) {
        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
            final PlatformTestingToolState state = wrapper.get();
            state.rebuildExpirationQueue();
        }
    }

    /**
     * Iterates on the {@link com.swirlds.virtualmap.VirtualMap} instances from the state
     * of the given {@code platform} to compute the next id to be used by account and smart contract entities.
     *
     * @param platform
     * 		A {@link Platform instance}
     * @return A pair of {@code Long}s, where {@code Pair.key()} returns the first id to be used by
     * 		account entities and {@code Pair.key()} returns the first id to be used by smart contracts.
     */
    private Pair<Long, Long> extractFirstIdForEntitiesFromSavedState(final Platform platform) {
        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {

            final PlatformTestingToolState state = wrapper.get();

            final AtomicLong maxAccountIdFromLoadedState = new AtomicLong(0);
            if (state.getVirtualMap() != null) {
                new MerkleIterator<VirtualLeafNode>(state.getVirtualMap())
                        .setFilter(node -> node instanceof VirtualLeafNode)
                        .forEachRemaining(leaf -> {
                            final AccountVirtualMapKey key = AccountVirtualMapKey.fromBytes(leaf.getKey());
                            maxAccountIdFromLoadedState.set(
                                    Math.max(key.getAccountID() + 1, maxAccountIdFromLoadedState.get()));
                        });
            }

            final AtomicLong maxSmartContractIdFromLoadedState = new AtomicLong(0);
            if (state.getVirtualMapForSmartContractsByteCode() != null) {
                new MerkleIterator<VirtualLeafNode>(state.getVirtualMapForSmartContractsByteCode())
                        .setFilter(node -> node instanceof VirtualLeafNode)
                        .forEachRemaining(leaf -> {
                            final SmartContractByteCodeMapKey key =
                                    SmartContractByteCodeMapKey.fromBytes(leaf.getKey());
                            maxSmartContractIdFromLoadedState.set(
                                    Math.max(key.getContractId() + 1, maxSmartContractIdFromLoadedState.get()));
                        });
            }

            return Pair.of(maxAccountIdFromLoadedState.get(), maxSmartContractIdFromLoadedState.get());
        }
    }

    /**
     * Register a {@link StateWriteToDiskCompleteListener} that finishes the test after saving a state in the final
     * freeze.
     */
    private void registerFinishAfterSaveStateDuringFreezeListener() {
        platform.getNotificationEngine().register(StateWriteToDiskCompleteListener.class, (notification) -> {
            if (notification.isFreezeState() && handledExitValidation.get()) {
                logSuccessMessageAndFinishTest(notification.getConsensusTimestamp());
            }
        });
    }

    private void handleMessageQuorum(final long id, final ControlAction state) {
        logger.info(
                DEMO_INFO.getMarker(),
                "Handling Quorum Transition [ triggeringNodeId = {}, type = {}, consensusTime = {} ]",
                () -> id,
                state::getType,
                state::getTimestamp);

        switch (state.getType()) {
            case ENTER_VALIDATION:
                handleEnterValidation(state.getTimestamp());
                break;
            case EXIT_VALIDATION:
                handleExitValidation(state.getTimestamp());
                break;
            case ENTER_SYNC:
                handleEnterSync(state.getTimestamp());
                break;
            case EXIT_SYNC:
                handleExitSync(state.getTimestamp());
                break;
        }
    }

    private void handleEnterValidation(final Instant consensusTime) {
        final Runnable fn = () -> {
            try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                    UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
                final PlatformTestingToolState state = wrapper.get();

                final String expectedMapFile =
                        createExpectedMapName(platform.getSelfId().id(), consensusTime);
                logger.info(
                        LOGM_DEMO_QUORUM,
                        "Achieved Quorum on ENTER_VALIDATION transaction [ expectedMapFile = {}, consensusTime = {} ]",
                        expectedMapFile,
                        consensusTime);

                VirtualMerkleTransactionHandler.handleExpectedMapValidation(
                        state.getStateExpectedMap(), state.getVirtualMap());

                serialize(
                        state.getStateExpectedMap().getExpectedMap(),
                        new File(STORAGE_DIRECTORY),
                        expectedMapFile,
                        false);

                logger.info(
                        LOGM_DEMO_QUORUM,
                        "Successfully wrote expected map to file [ expectedMapFile = {}, consensusTime = {} ]",
                        expectedMapFile,
                        consensusTime);

                submitter.sendTransaction(
                        platform, pttTransactionPool.createControlTranBytes(ControlType.EXIT_VALIDATION));

                logger.info(
                        LOGM_DEMO_QUORUM, "Sent EXIT_VALIDATION transaction  [ consensusTime = {} ]", consensusTime);
            }
        };

        new ThreadConfiguration(getStaticThreadManager())
                .setNodeId(platform.getSelfId())
                .setComponent(PTT_COMPONENT)
                .setThreadName(ENTER_VALIDATION_THREAD_NAME)
                .setRunnable(fn)
                .build()
                .start();
    }

    private void handleExitValidation(final Instant consensusTime) {
        final Runnable fn = () -> {
            logger.info(
                    LOGM_DEMO_QUORUM,
                    "Achieved Quorum on EXIT_VALIDATION transaction [ consensusTime = {} ]",
                    consensusTime);
            handledExitValidation.set(true);

            // the first node sends a freeze transaction after all transaction finish
            // This is for guaranteeing that all nodes generated same amount of signed states
            if (platform.getSelfId().id() == 0) {
                sendFreezeTransaction();
            }

            if (waitForSaveStateDuringFreeze) {
                logger.info(LOGM_DEMO_QUORUM, "Waiting for final state to save before terminating");
            } else {
                // exit test if no other conditions exist
                logSuccessMessageAndFinishTest(consensusTime);
            }
        };

        new ThreadConfiguration(getStaticThreadManager())
                .setNodeId(platform.getSelfId())
                .setComponent(PTT_COMPONENT)
                .setThreadName(EXIT_VALIDATION_THREAD_NAME)
                .setRunnable(fn)
                .build()
                .start();
    }

    private void handleEnterSync(final Instant consensusTime) {
        logger.info(
                LOGM_DEMO_QUORUM, "Achieved Quorum on ENTER_SYNC transaction [ consensusTime = {} ]", consensusTime);

        submitter.sendTransaction(platform, pttTransactionPool.createControlTranBytes(ControlType.EXIT_SYNC));

        logger.info(LOGM_DEMO_QUORUM, "Sent EXIT_SYNC transaction  [ consensusTime = {} ]", consensusTime);
    }

    private void handleExitSync(final Instant consensusTime) {
        logger.info(LOGM_DEMO_QUORUM, "Achieved Quorum on EXIT_SYNC transaction [ consensusTime = {} ]", consensusTime);
    }

    private interface ValueExtractor {
        long getValue(TransactionCounter counter);
    }

    private void logSuccessMessageAndFinishTest(final Instant consensusTime) {
        final long sleepTime = submitter.getSubmitConfig().getSleepAfterTestMs();
        logger.info(
                LOGM_DEMO_QUORUM,
                "Preparing to terminate the JVM [ sleepAfterTestMs = {}, consensusTime = {} ]",
                sleepTime,
                consensusTime);

        logger.info(
                LOGM_DEMO_INFO,
                () -> new ApplicationFinishedPayload(
                        "Test success: Reached quorum on the EXIT_VALIDATION transaction, consensus time = "
                                + consensusTime.toString()));

        try {
            Thread.sleep(sleepTime);
        } catch (final InterruptedException e) {
            logger.info(LOGM_DEMO_QUORUM, "exit-validator thread was interrupted. Continuing process exit.");
            Thread.currentThread().interrupt();
        }

        if (currentConfig.isQuitJVMAfterTest()) {
            logger.info(LOGM_DEMO_QUORUM, "Terminating the JVM [ consensusTime = {} ]", consensusTime);
            SystemExitUtils.exitSystem(SystemExitCode.NO_ERROR);
        }
    }

    /**
     * send queries for getting MapValue of a MapKey in current state
     */
    private void queryInState() {
        // time in nanoseconds between successive queries
        final long periodInNanos = UnitConstants.SECONDS_TO_NANOSECONDS / queriesSentPerSec;

        final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(3);
        final ScheduledFuture<?> future = scheduledThreadPoolExecutor.scheduleAtFixedRate(
                () -> {
                    try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                            UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
                        // this watch is for counting the time cost in each query on current state
                        final StopWatch watch = new StopWatch();
                        watch.start();
                        final PlatformTestingToolState state = wrapper.get();
                        // suspend because we don't want to count the time spent on picking up a MapKey for this query
                        watch.suspend();
                        if (state != null) {
                            final MapKey mapKey = state.getStateExpectedMap().getMapKeyForQuery(Crypto);
                            // resume after picking up a MapKey
                            watch.resume();
                            // query value of the key
                            final MapValueData value =
                                    state.getStateMap().getMap().get(mapKey);
                            if (value != null) {
                                value.getBalance();
                            }
                            watch.stop();
                            // record time spent on getting current state and getting result of querying the state
                            queryLeafTimeCostMicroSec.update(watch.getTime(TimeUnit.MICROSECONDS));
                            queriesAnsweredPerSecond.update(1);
                        } else {
                            watch.stop();
                        }
                    }
                },
                0,
                periodInNanos,
                TimeUnit.NANOSECONDS);

        final Thread queryInStateThread = new Thread(() -> {
            try {
                future.get();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (final ExecutionException ex) {
                logger.error(EXCEPTION.getMarker(), "Got Exception in queryInState() ", ex);
            }
        });
        queryInStateThread.setName("queryInState_node" + selfId);
        queryInStateThread.start();
    }

    @Override
    @NonNull
    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        final com.swirlds.demo.platform.fs.stresstest.proto.StateSignatureTransaction convertedSystemTransaction =
                com.swirlds.demo.platform.fs.stresstest.proto.StateSignatureTransaction.newBuilder()
                        .setRound(transaction.round())
                        .setSignature(
                                ByteString.copyFrom(transaction.signature().toByteArray()))
                        .setHash(ByteString.copyFrom(transaction.hash().toByteArray()))
                        .build();
        final TestTransaction testTransaction = TestTransaction.newBuilder()
                .setStateSignatureTransaction(convertedSystemTransaction)
                .build();

        if (currentConfig.getPayloadConfig().isAppendSig()) {
            final TestTransactionWrapper testTransactionWrapper = TestTransactionWrapper.newBuilder()
                    .setTestTransactionRawBytes(ByteString.copyFrom(testTransaction.toByteArray()))
                    .build();
            return Bytes.wrap(testTransactionWrapper.toByteArray());
        } else {
            return Bytes.wrap(testTransaction.toByteArray());
        }
    }
}
