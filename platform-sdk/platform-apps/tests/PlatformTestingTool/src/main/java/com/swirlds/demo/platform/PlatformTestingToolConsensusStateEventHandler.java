// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.base.units.UnitConstants.MICROSECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction.BodyCase.FCMTRANSACTION;
import static com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction.BodyCase.STATESIGNATURETRANSACTION;
import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.STORAGE_DIRECTORY;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.createExpectedMapName;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.SaveExpectedMapHandler.serialize;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_11_0;
import static org.hiero.base.utility.CommonUtils.hex;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.utility.ThresholdLimitingHandler;
import com.swirlds.demo.merkle.map.FCMTransactionHandler;
import com.swirlds.demo.merkle.map.FCMTransactionUtils;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.actions.Action;
import com.swirlds.demo.platform.actions.QuorumResult;
import com.swirlds.demo.platform.actions.QuorumTriggeredAction;
import com.swirlds.demo.platform.freeze.FreezeTransactionHandler;
import com.swirlds.demo.platform.fs.stresstest.proto.Activity;
import com.swirlds.demo.platform.fs.stresstest.proto.AppTransactionSignatureType;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.FreezeTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.RandomBytesTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.SimpleAction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransactionWrapper;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.platform.iss.IssLeaf;
import com.swirlds.demo.platform.nft.NftLedgerStatistics;
import com.swirlds.demo.virtualmerkle.transaction.handler.VirtualMerkleTransactionHandler;
import com.swirlds.logging.legacy.payload.SoftwareVersionPayload;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;
import org.hiero.base.crypto.VerificationStatus;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.roster.RosterUtils;

public class PlatformTestingToolConsensusStateEventHandler
        implements ConsensusStateEventHandler<PlatformTestingToolState> {

    private static final Logger logger = LogManager.getLogger(PlatformTestingToolState.class);
    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker LOGM_EXPIRATION = MarkerManager.getMarker("EXPIRATION");
    private static final Marker LOGM_STARTUP = MarkerManager.getMarker("STARTUP");
    private static final long EXCEPTION_RATE_THRESHOLD = 10;

    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    static final String STAT_TIMER_THREAD_NAME = "stat timer PTTState";

    /**
     * Defined in settings. the consensus timestamp of a transaction is guaranteed to be at least this many nanoseconds
     * later than that of the transaction immediately before it in consensus order, and to be a multiple of this
     */
    private static final long minTransTimestampIncrNanos = 1_000;

    /**
     * statistics for handleTransaction
     */
    private static final String HANDLE_TRANSACTION_CATEGORY = "HandleTransaction";

    private static long htCountFCM;
    private static long htFCMSumNano;
    private static final RunningAverageMetric.Config HT_FCM_MICRO_SEC_CONFIG = new RunningAverageMetric.Config(
                    HANDLE_TRANSACTION_CATEGORY, "htFCMTimeMicroSec")
            .withDescription("average handleTransaction (FCM) Time, microseconds");
    private static RunningAverageMetric htFCMMicroSec;
    private static long htCountFCQ;
    private static long htFCQSumNano;

    private static final RunningAverageMetric.Config HT_FCQ_MICRO_SEC_CONFIG = new RunningAverageMetric.Config(
                    HANDLE_TRANSACTION_CATEGORY, "htFCQTimeMicroSec")
            .withDescription("average handleTransaction (FCQ) Time, microseconds");
    private static RunningAverageMetric htFCQMicroSec;

    /**
     * Has init() been called on this copy or an ancestor copy of this object?
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private static long htCountExpiration;
    private static long htFCQExpirationSumMicro;

    private static final RunningAverageMetric.Config HT_FCQ_EXPIRATION_MICRO_SEC_CONFIG =
            new RunningAverageMetric.Config(HANDLE_TRANSACTION_CATEGORY, "htFCQExpirationMicroSec")
                    .withDescription("FCQ Expiration Time per call, microseconds");
    private static RunningAverageMetric htFCQExpirationMicroSec;

    private static final RunningAverageMetric.Config HT_FCM_SIZE_CONFIG = new RunningAverageMetric.Config(
                    HANDLE_TRANSACTION_CATEGORY, "htFCMSize")
            .withDescription("FCM Tree Size (accounts)")
            .withFormat(FORMAT_11_0);
    private static RunningAverageMetric htFCMSize;

    ///////////////////////////////////////////
    // Transaction Handlers
    private static long htFCMAccounts;

    private static final RunningAverageMetric.Config HT_FCQ_SIZE_CONFIG = new RunningAverageMetric.Config(
                    HANDLE_TRANSACTION_CATEGORY, "htFCQSize")
            .withDescription("FCQ Tree Size (accounts)")
            .withFormat(FORMAT_11_0);
    private static RunningAverageMetric htFCQSize;

    private static long htFCQAccounts;

    private static final RunningAverageMetric.Config HT_FCQ_RECORDS_CONFIG = new RunningAverageMetric.Config(
                    HANDLE_TRANSACTION_CATEGORY, "htFCQRecords")
            .withDescription("FCQ Transaction Records")
            .withFormat(FORMAT_11_0);
    private static RunningAverageMetric htFCQRecords;

    private static long htFCQRecordsCount;
    private final PlatformStateFacade platformStateFacade;
    ///////////////////////////////////////////
    // Non copyable shared variables
    private Platform platform;
    ///////////////////////////////////////////
    // Copyable variables
    private ThresholdLimitingHandler<Throwable> exceptionRateLimiter;
    private ProgressCfg progressCfg;
    ///////////////////////////////////////////
    // Variables not used for state copyTo
    protected long roundCounter = 0;

    // last timestamp purging records
    private long lastPurgeTimestamp = 0;
    /**
     * The instant of the previously handled transaction. Null if no transactions have yet been handled by this state
     * instance. Used to verify that each transaction happens at a later instant than its predecessor.
     */
    private Instant previousTimestamp;
    /**
     * Handles quorum determinations for all {@link ControlTransaction} processed by the handle method.
     */
    private QuorumTriggeredAction<ControlAction> controlQuorum;

    /** The round number of the freeze round */
    private long freezeRound = -1;

    public PlatformTestingToolConsensusStateEventHandler(@NonNull final PlatformStateFacade platformStateFacade) {
        this.platformStateFacade = platformStateFacade;
    }

    /**
     * startup any statistics
     *
     * @param platform Platform
     */
    public static void initStatistics(final Platform platform) {
        if (htFCMMicroSec != null) {
            return;
        }

        /* Add handleTransaction statistics */
        htFCQMicroSec = platform.getContext().getMetrics().getOrCreate(HT_FCQ_MICRO_SEC_CONFIG);
        htFCQExpirationMicroSec = platform.getContext().getMetrics().getOrCreate(HT_FCQ_EXPIRATION_MICRO_SEC_CONFIG);
        htFCMSize = platform.getContext().getMetrics().getOrCreate(HT_FCM_SIZE_CONFIG);
        htFCQSize = platform.getContext().getMetrics().getOrCreate(HT_FCQ_SIZE_CONFIG);
        htFCQRecords = platform.getContext().getMetrics().getOrCreate(HT_FCQ_RECORDS_CONFIG);

        NftLedgerStatistics.register(platform);

        // timer to update output stats
        final int SAMPLING_PERIOD = 5000; // millisecond
        final Timer statTimer = new Timer(STAT_TIMER_THREAD_NAME, true);
        statTimer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        getCurrentTransactionStat();
                    }
                },
                0,
                SAMPLING_PERIOD);
    }

    private static void getCurrentTransactionStat() {
        if (htFCMMicroSec == null) {
            return;
        }

        // fcm, nsec
        if (htCountFCM > 0) {
            htFCMMicroSec.update((double) htFCMSumNano / (double) htCountFCM * NANOSECONDS_TO_MICROSECONDS);
        } else {
            htFCMMicroSec.update(0);
        }
        htFCMSumNano = 0;
        htCountFCM = 0;
        // fcq, nsec
        if (htCountFCQ > 0) {
            htFCQMicroSec.update((double) htFCQSumNano / (double) htCountFCQ * NANOSECONDS_TO_MICROSECONDS);
        } else {
            htFCQMicroSec.update(0);
        }
        htFCQSumNano = 0;
        htCountFCQ = 0;
        // expiration, in microseconds
        if (htCountExpiration > 0) {
            htFCQExpirationMicroSec.update((double) htFCQExpirationSumMicro / (double) htCountExpiration);
        } else {
            htFCQExpirationMicroSec.update(0);
        }
        htFCQExpirationSumMicro = 0;
        htCountExpiration = 0;

        // datastructure sizes
        htFCMSize.update(htFCMAccounts);
        htFCQSize.update(htFCQAccounts);
        htFCQRecords.update(htFCQRecordsCount);
    }

    // count invalid signature ratio
    static AtomicLong totalTransactionSignatureCount = new AtomicLong(0);
    static AtomicLong expectedInvalidSignatureCount = new AtomicLong(0);

    QuorumTriggeredAction<ControlAction> getControlQuorum() {
        return controlQuorum;
    }

    void initControlStructures(final Action<Long, ControlAction> action) {
        final int nodeIndex =
                RosterUtils.getIndex(platform.getRoster(), platform.getSelfId().id());
        this.controlQuorum = new QuorumTriggeredAction<>(
                () -> nodeIndex,
                () -> platform.getRoster().rosterEntries().size(),
                () -> RosterUtils.getNumberWithWeight(platform.getRoster()),
                action);

        this.exceptionRateLimiter = new ThresholdLimitingHandler<>(EXCEPTION_RATE_THRESHOLD);
    }

    /**
     * Make sure that the timestamp for the submitted transaction is not earlier than (the timestamp for the previous
     * transaction + minTransTimestampIncrNanos).
     */
    private void validateTimestamp(final Instant timestamp) {
        if (previousTimestamp != null) {
            final Instant previousTransTimestampPlusIncr = previousTimestamp.plusNanos(minTransTimestampIncrNanos);
            if (timestamp.isBefore(previousTransTimestampPlusIncr)) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Transaction has timestamp {} which is earlier than previous timestamp {} plus {} nanos: "
                                + "{}",
                        timestamp,
                        previousTimestamp,
                        minTransTimestampIncrNanos,
                        previousTransTimestampPlusIncr);
            }
        }
        previousTimestamp = timestamp;
    }

    /**
     * Check if the signatures on the transaction are invalid.
     *
     * @return true if the signature is invalid
     */
    private boolean checkIfSignatureIsInvalid(final Transaction trans, @NonNull final PlatformTestingToolState state) {
        if (state.getConfig().isAppendSig()) {
            return validateSignatures(trans);
        }
        return false;
    }

    /**
     * If configured, delay this transaction.
     */
    private void delay(@NonNull final PlatformTestingToolState state) {
        if (state.getConfig().getDelayCfg() != null) {
            final int delay = state.getConfig().getDelayCfg().getRandomDelay();
            try {
                Thread.sleep(delay);
            } catch (final InterruptedException e) {
                logger.info(LOGM_DEMO_INFO, "", e);
            }
        }
        SyntheticBottleneckConfig.getActiveConfig()
                .throttleIfNeeded(platform.getSelfId().id());
    }

    /**
     * Instantiate TestTransaction object from transaction byte array.
     *
     * @return the instantiated transaction if no errors, otherwise null
     */
    private Optional<TestTransaction> unpackTransaction(
            final Transaction trans, @NonNull final PlatformTestingToolState state) {
        try {
            final byte[] payloadBytes = trans.getApplicationTransaction().toByteArray();
            if (state.getConfig().isAppendSig()) {
                final byte[] testTransactionRawBytes = TestTransactionWrapper.parseFrom(payloadBytes)
                        .getTestTransactionRawBytes()
                        .toByteArray();
                return Optional.of(TestTransaction.parseFrom(testTransactionRawBytes));
            } else {
                return Optional.of(TestTransaction.parseFrom(payloadBytes));
            }
        } catch (final InvalidProtocolBufferException ex) {
            exceptionRateLimiter.handle(
                    ex, (error) -> logger.error(EXCEPTION.getMarker(), "InvalidProtocolBufferException", error));
            return Optional.empty();
        }
    }

    /**
     * If configured to do so, purge expired records as needed.
     */
    private void purgeExpiredRecordsIfNeeded(
            final TestTransaction testTransaction,
            final Instant timestamp,
            @NonNull final PlatformTestingToolState state) {
        if (!testTransaction.hasControlTransaction()
                || testTransaction.getControlTransaction().getType() != ControlType.EXIT_VALIDATION) {

            if (!state.getFcmFamily().getAccountFCQMap().isEmpty()) {
                // remove expired records from FCQs before handling transaction if accountFCQMap has any entities
                try {
                    purgeExpiredRecords(state, timestamp.getEpochSecond());
                } catch (final Throwable ex) {
                    exceptionRateLimiter.handle(
                            ex,
                            (error) -> logger.error(EXCEPTION.getMarker(), "Failed to purge expired records", error));
                }
            }
        }
    }

    /**
     * Write a special log message if this is the first transaction and total fcm and any file statistics are all
     * zeroes.
     */
    private void logIfFirstTransaction(final NodeId id, @NonNull final PlatformTestingToolState state) {
        final int nodeIndex = RosterUtils.getIndex(platform.getRoster(), id.id());
        if (progressCfg != null
                && progressCfg.getProgressMarker() > 0
                && state.getTransactionCounter().get(nodeIndex).getAllTransactionAmount() == 0) {
            logger.info(LOGM_DEMO_INFO, "PlatformTestingDemo HANDLE ALL START");
        }
    }

    /**
     * Handle the random bytes transaction type.
     */
    private void handleBytesTransaction(
            @NonNull final TestTransaction testTransaction,
            @NonNull final NodeId id,
            @NonNull final PlatformTestingToolState state) {
        Objects.requireNonNull(testTransaction, "testTransaction must not be null");
        Objects.requireNonNull(id, "id must not be null");
        final int nodeIndex = RosterUtils.getIndex(platform.getRoster(), id.id());
        final RandomBytesTransaction bytesTransaction = testTransaction.getBytesTransaction();
        if (bytesTransaction.getIsInserSeq()) {
            final long seq = Utilities.toLong(bytesTransaction.getData().toByteArray());
            if (state.getNextSeqCons().get(nodeIndex).getValue() != seq) {
                logger.error(
                        EXCEPTION.getMarker(),
                        platform.getSelfId() + " error, new (id=" + id
                                + ") seq should be " + state.getNextSeqCons().get(nodeIndex)
                                + " but is " + seq);
            }
            state.getNextSeqCons().get(nodeIndex).getAndIncrement();
        }
    }

    /**
     * Handle the Virtual Merkle transaction type.
     */
    private void handleVirtualMerkleTransaction(
            @NonNull final VirtualMerkleTransaction virtualMerkleTransaction,
            @NonNull final NodeId id,
            @NonNull final Instant consensusTimestamp,
            @NonNull final PlatformTestingToolState state) {
        Objects.requireNonNull(virtualMerkleTransaction, "virtualMerkleTransaction must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(consensusTimestamp, "consensusTimestamp must not be null");
        final int nodeIndex = RosterUtils.getIndex(platform.getRoster(), id.id());
        VirtualMerkleTransactionHandler.handle(
                consensusTimestamp,
                virtualMerkleTransaction,
                state.getStateExpectedMap(),
                state.getVirtualMap(),
                state.getVirtualMapForSmartContracts(),
                state.getVirtualMapForSmartContractsByteCode());

        if (virtualMerkleTransaction.hasCreateAccount()) {
            state.getTransactionCounter().get(nodeIndex).vmCreateAmount++;
        } else if (virtualMerkleTransaction.hasUpdateAccount()) {
            state.getTransactionCounter().get(nodeIndex).vmUpdateAmount++;
        } else if (virtualMerkleTransaction.hasDeleteAccount()) {
            state.getTransactionCounter().get(nodeIndex).vmDeleteAmount++;
        } else if (virtualMerkleTransaction.hasSmartContract()) {
            state.getTransactionCounter().get(nodeIndex).vmContractCreateAmount++;
        } else if (virtualMerkleTransaction.hasMethodExecution()) {
            state.getTransactionCounter().get(nodeIndex).vmContractExecutionAmount++;
        }
    }

    /**
     * Handle the FCM transaction type.
     */
    private void handleFCMTransaction(
            @NonNull final TestTransaction testTransaction,
            @NonNull final NodeId id,
            @NonNull final Instant timestamp,
            final boolean invalidSig,
            @NonNull final PlatformTestingToolState state) {
        Objects.requireNonNull(testTransaction, "testTransaction must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        final int nodeIndex = RosterUtils.getIndex(platform.getRoster(), id.id());
        final FCMTransaction fcmTransaction = testTransaction.getFcmTransaction();
        final ExpectedFCMFamily expectedFCMFamily = state.getStateExpectedMap();

        // Handle Activity transaction, which doesn't affect any entity's lifecyle
        if (fcmTransaction.hasActivity()) {
            final Activity.ActivityType activityType =
                    fcmTransaction.getActivity().getType();
            if (nodeIndex == 0 && activityType == Activity.ActivityType.SAVE_EXPECTED_MAP) {
                // Serialize ExpectedMap to disk in JSON format
                TransactionSubmitter.setForcePauseCanSubmitMore(new AtomicBoolean(true));
                serialize(
                        expectedFCMFamily.getExpectedMap(),
                        new File(STORAGE_DIRECTORY),
                        createExpectedMapName(platform.getSelfId().id(), timestamp),
                        false);
                TransactionSubmitter.setForcePauseCanSubmitMore(new AtomicBoolean(false));
                logger.info(LOGM_DEMO_INFO, "handling SAVE_EXPECTED_MAP");

            } else if (activityType == Activity.ActivityType.SAVE_EXPECTED_MAP) {
                logger.info(LOGM_DEMO_INFO, "Received SAVE_EXPECTED_MAP transaction from node {}", id);
            } else {
                logger.info(EXCEPTION.getMarker(), "unknown Activity type");
            }
            return;
        }

        if (fcmTransaction.hasDummyTransaction()) {
            return;
        }

        // Extract MapKeys, TransactionType, and EntityType from FCMTransaction
        // which might affect entity's lifecycle status
        final List<MapKey> keys = FCMTransactionUtils.getMapKeys(fcmTransaction);
        final TransactionType transactionType = FCMTransactionUtils.getTransactionType(fcmTransaction);
        final EntityType entityType = FCMTransactionUtils.getEntityType(fcmTransaction);
        final long epochMillis = timestamp.toEpochMilli();

        final long originId = fcmTransaction.getOriginNode();

        if ((keys.isEmpty() && entityType != EntityType.NFT) || transactionType == null || entityType == null) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Invalid Transaction: keys: {}, transactionType: {}, entityType: {}",
                    keys,
                    transactionType,
                    entityType);
            return;
        }

        // if there is any error, we don't handle this transaction
        if (!expectedFCMFamily.shouldHandleForKeys(
                        keys, transactionType, state.getConfig(), entityType, epochMillis, originId)
                && entityType != EntityType.NFT) {
            logger.info(DEMO_INFO.getMarker(), "A transaction ignored by expected map: {}.", roundCounter);
            return;
        }

        // If signature verification result doesn't match expected result
        // which is set when generating this FCMTransaction, it denotes an error
        if (state.getConfig().isAppendSig() && invalidSig != fcmTransaction.getInvalidSig()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Unexpected signature verification result: " + "actual: {}; expected:{}",
                    () -> (invalidSig ? "INVALID" : "VALID"),
                    () -> (fcmTransaction.getInvalidSig() ? "INVALID" : "VALID"));

            // if the entity doesn't exist, put it to expectedMap;
            // set its ExpectedValues's isErrored to be true;
            // set its latestHandledStatus to be INVALID_SIG
            expectedFCMFamily.setLatestHandledStatusForKey(
                    keys.getFirst(),
                    entityType,
                    null,
                    TransactionState.INVALID_SIG,
                    transactionType,
                    epochMillis,
                    originId,
                    true);
            return;
        }

        // for expected invalid sig,
        // set expectedValue's latestHandledStatus to be EXPECTED_INVALID_SIG
        // and handle this transaction
        if (invalidSig) {
            expectedFCMFamily.setLatestHandledStatusForKey(
                    keys.getFirst(),
                    entityType,
                    null,
                    TransactionState.EXPECTED_INVALID_SIG,
                    transactionType,
                    epochMillis,
                    originId,
                    false);
        }

        // Handle the transaction and set latestHandledStatus
        try {
            FCMTransactionHandler.performOperation(
                    fcmTransaction,
                    state,
                    expectedFCMFamily,
                    originId,
                    epochMillis,
                    entityType,
                    timestamp.getEpochSecond() + state.getConfig().getFcqTtl(),
                    state.getExpirationQueue(),
                    state.getAccountsWithExpiringRecords());
        } catch (final Exception ex) {
            exceptionRateLimiter.handle(
                    ex,
                    (error) -> logger.error(
                            EXCEPTION.getMarker(),
                            "Exceptions while handling transaction: {} {} for {}, originId:{}",
                            transactionType,
                            entityType,
                            keys,
                            originId,
                            error));

            // if the entity doesn't exist, put it to expectedMap;
            // set its ExpectedValues's isErrored to be true;
            // set its latestHandledStatus to be HANDLE_FAILED
            for (final MapKey key : keys) {
                expectedFCMFamily.setLatestHandledStatusForKey(
                        key,
                        entityType,
                        null,
                        TransactionState.HANDLE_FAILED,
                        transactionType,
                        timestamp.toEpochMilli(),
                        originId,
                        true);
            }
        }
        if (fcmTransaction.hasCreateAccount()) {
            state.getTransactionCounter().get(nodeIndex).fcmCreateAmount++;
            if (progressCfg != null) {
                logProgress(
                        id,
                        progressCfg.getProgressMarker(),
                        PAYLOAD_TYPE.TYPE_FCM_CREATE,
                        progressCfg.getExpectedFCMCreateAmount(),
                        state.getTransactionCounter().get(nodeIndex).fcmCreateAmount);
            }
        } else if (fcmTransaction.hasTransferBalance()) {
            state.getTransactionCounter().get(nodeIndex).fcmTransferAmount++;
            if (progressCfg != null) {
                logProgress(
                        id,
                        progressCfg.getProgressMarker(),
                        PAYLOAD_TYPE.TYPE_FCM_TRANSFER,
                        progressCfg.getExpectedFCMTransferAmount(),
                        state.getTransactionCounter().get(nodeIndex).fcmTransferAmount);
            }
        } else if (fcmTransaction.hasDeleteAccount()) {
            state.getTransactionCounter().get(nodeIndex).fcmDeleteAmount++;
            if (progressCfg != null) {
                logProgress(
                        id,
                        progressCfg.getProgressMarker(),
                        PAYLOAD_TYPE.TYPE_FCM_DELETE,
                        progressCfg.getExpectedFCMDeleteAmount(),
                        state.getTransactionCounter().get(nodeIndex).fcmDeleteAmount);
            }
        } else if (fcmTransaction.hasUpdateAccount()) {
            state.getTransactionCounter().get(nodeIndex).fcmUpdateAmount++;
            if (progressCfg != null) {
                logProgress(
                        id,
                        progressCfg.getProgressMarker(),
                        PAYLOAD_TYPE.TYPE_FCM_UPDATE,
                        progressCfg.getExpectedFCMUpdateAmount(),
                        state.getTransactionCounter().get(nodeIndex).fcmUpdateAmount);
            }
        } else if (fcmTransaction.hasAssortedAccount()) {
            state.getTransactionCounter().get(nodeIndex).fcmAssortedAmount++;
            if (progressCfg != null) {
                logProgress(
                        id,
                        progressCfg.getProgressMarker(),
                        PAYLOAD_TYPE.TYPE_FCM_ASSORTED,
                        progressCfg.getExpectedFCMAssortedAmount(),
                        state.getTransactionCounter().get(nodeIndex).fcmAssortedAmount);
            }
        } else if (fcmTransaction.hasAssortedFCQ()) {
            state.getTransactionCounter().get(nodeIndex).fcmFCQAssortedAmount++;
        } else if (fcmTransaction.hasCreateAccountFCQ()) {
            state.getTransactionCounter().get(nodeIndex).fcmFCQCreateAmount++;
        } else if (fcmTransaction.hasUpdateAccountFCQ()) {
            state.getTransactionCounter().get(nodeIndex).fcmFCQUpdateAmount++;
        } else if (fcmTransaction.hasTransferBalanceFCQ()) {
            state.getTransactionCounter().get(nodeIndex).fcmFCQTransferAmount++;
        } else if (fcmTransaction.hasDeleteFCQNode()) {
            state.getTransactionCounter().get(nodeIndex).fcmFCQDeleteAmount++;
        }
    }

    /**
     * Handle the control transaction type.
     */
    private void handleControlTransaction(
            @NonNull final TestTransaction testTransaction,
            @NonNull final NodeId id,
            @NonNull final Instant timestamp,
            @NonNull final PlatformTestingToolState state) {
        Objects.requireNonNull(testTransaction, "testTransaction must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        final long nodeIndex = RosterUtils.getIndex(platform.getRoster(), id.id());
        final ControlTransaction msg = testTransaction.getControlTransaction();
        logger.info(
                DEMO_INFO.getMarker(),
                "Handling Control Transaction [ originatingNodeId = {}, type = {}, consensusTimestamp = {} ]",
                () -> id,
                msg::getType,
                () -> timestamp);

        // Must use auto reset here, otherwise if reached quorum EXIT_VALIDATION then PTT restart, QuorumResult would be
        // reloaded from saved state with reaching quorum state EXIT_VALIDATION as true,
        // then TransactionSubmitter won't be able to submit transaction due to some check mechanism.
        controlQuorum.withAutoReset().check(nodeIndex, new ControlAction(timestamp, msg.getType()));
        // updating quorum result after handling control transactions
        state.setQuorumResult(controlQuorum.getQuorumResult().copy());
    }

    /**
     * Handle the freeze transaction type.
     */
    private void handleFreezeTransaction(final TestTransaction testTransaction, final State state) {
        final FreezeTransaction freezeTx = testTransaction.getFreezeTransaction();
        FreezeTransactionHandler.freeze(freezeTx, platformStateFacade, state);
    }

    /**
     * Handle a simple action transaction type
     */
    private void handleSimpleAction(final SimpleAction simpleAction, final PlatformTestingToolState state) {
        if (simpleAction == SimpleAction.CAUSE_ISS) {
            state.getIssLeaf().setWriteRandom(true);
        }
    }

    private void preHandleTransaction(
            final Transaction transaction,
            final Event event,
            final PlatformTestingToolState state,
            Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        try {
            final byte[] payloadBytes = transaction.getApplicationTransaction().toByteArray();
            final TestTransactionWrapper testTransactionWrapper = TestTransactionWrapper.parseFrom(payloadBytes);
            final byte[] testTransactionRawBytes =
                    testTransactionWrapper.getTestTransactionRawBytes().toByteArray();
            final TestTransaction testTransaction = TestTransaction.parseFrom(testTransactionRawBytes);

            if (testTransaction.getBodyCase() == STATESIGNATURETRANSACTION) {
                consumeSystemTransaction(
                        testTransaction,
                        event.getCreatorId(),
                        event.getSoftwareVersion(),
                        stateSignatureTransactionCallback);
            } else {
                expandSignatures(transaction, testTransactionWrapper, state);
            }
        } catch (final InvalidProtocolBufferException ex) {
            exceptionRateLimiter.handle(
                    ex, (error) -> logger.error(EXCEPTION.getMarker(), "InvalidProtocolBufferException", error));
        }
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull PlatformTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        state.throwIfImmutable();
        if (!initialized.get()) {
            throw new IllegalStateException("onHandleConsensusRound() called before init()");
        }
        delay(state);
        updateTransactionCounters(state);
        round.forEachEventTransaction((event, transaction) ->
                handleConsensusTransaction(event, transaction, state, stateSignatureTransactionCallback));
        if (platformStateFacade.isFreezeRound(state, round)) {
            freezeRound = round.getRoundNum();
        }
    }

    /**
     * If the size of the address book has changed, zero out the transaction counters and resize, as needed.
     */
    private void updateTransactionCounters(PlatformTestingToolState state) {
        if (state.getTransactionCounter() == null
                || state.getTransactionCounter().size()
                        != platform.getRoster().rosterEntries().size()) {
            state.setNextSeqCons(
                    new NextSeqConsList(platform.getRoster().rosterEntries().size()));

            logger.info(DEMO_INFO.getMarker(), "resetting transaction counters");

            state.setTransactionCounter(new TransactionCounterList(
                    platform.getRoster().rosterEntries().size()));
            for (int id = 0; id < platform.getRoster().rosterEntries().size(); id++) {
                state.getTransactionCounter().add(new TransactionCounter(id));
            }
        }
    }

    private void handleConsensusTransaction(
            final ConsensusEvent event,
            final ConsensusTransaction trans,
            final PlatformTestingToolState state,
            final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        handleTransaction(
                event.getCreatorId(),
                event.getSoftwareVersion(),
                event.getTimeCreated(),
                trans.getConsensusTimestamp(),
                trans,
                state,
                stateSignatureTransactionCallback);
    }

    private void handleTransaction(
            @NonNull final NodeId id,
            @NonNull final SemanticVersion semanticVersion,
            @NonNull final Instant timeCreated,
            @NonNull final Instant timestamp,
            @NonNull final ConsensusTransaction trans,
            @NonNull final PlatformTestingToolState state,
            @NonNull
                    final Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        if (state.getConfig().isAppendSig()) {
            try {
                final TestTransactionWrapper testTransactionWrapper = TestTransactionWrapper.parseFrom(
                        trans.getApplicationTransaction().toByteArray());
                final byte[] testTransactionRawBytes =
                        testTransactionWrapper.getTestTransactionRawBytes().toByteArray();
                final byte[] publicKey =
                        testTransactionWrapper.getPublicKeyRawBytes().toByteArray();
                final byte[] signature =
                        testTransactionWrapper.getSignaturesRawBytes().toByteArray();

                // if this is expected manually inject invalid signature
                boolean expectingInvalidSignature = false;
                final TestTransaction testTransaction = TestTransaction.parseFrom(testTransactionRawBytes);

                if (testTransaction.getBodyCase() == STATESIGNATURETRANSACTION) {
                    consumeSystemTransaction(testTransaction, id, semanticVersion, stateSignatureTransactionCallback);
                    return;
                }
                if (testTransaction.getBodyCase() == FCMTRANSACTION) {
                    final FCMTransaction fcmTransaction = testTransaction.getFcmTransaction();
                    if (fcmTransaction.getInvalidSig()) {
                        expectingInvalidSignature = true;
                    }
                }
                totalTransactionSignatureCount.incrementAndGet();
                final TransactionSignature s = trans.getMetadata();
                if (s != null && s.getSignatureStatus() != VerificationStatus.VALID && (!expectingInvalidSignature)) {
                    logger.error(
                            EXCEPTION.getMarker(),
                            "Invalid Transaction Signature [status = {}, signatureType = {}, "
                                    + "publicKey = {}, signature = {}, data = {}, "
                                    + "actualPublicKey = {}, actualSignature = {}, actualData = {} ]",
                            s.getSignatureStatus(),
                            s.getSignatureType(),
                            hex(publicKey),
                            hex(signature),
                            hex(testTransactionRawBytes),
                            hex(s.getPublicKey()),
                            hex(s.getSignature()),
                            hex(s.getMessage()));
                } else if (s != null
                        && s.getSignatureStatus() != VerificationStatus.VALID
                        && expectingInvalidSignature) {
                    expectedInvalidSignatureCount.incrementAndGet();
                }

            } catch (final InvalidProtocolBufferException ex) {
                exceptionRateLimiter.handle(
                        ex,
                        (error) -> logger.error(
                                EXCEPTION.getMarker(),
                                "" + "InvalidProtocolBufferException while checking signature",
                                error));
            }
        }

        //////////// start timing/////////////
        final long startTime = System.nanoTime();
        validateTimestamp(timestamp);

        final Optional<TestTransaction> testTransaction = unpackTransaction(trans, state);
        if (testTransaction.isEmpty()) {
            return;
        }
        final long splitTime1 = System.nanoTime();
        // omit entityExpiration from handleTransaction timing
        purgeExpiredRecordsIfNeeded(testTransaction.get(), timestamp, state);
        final long splitTime2 = System.nanoTime();

        logIfFirstTransaction(id, state);

        // Handle based on transaction type
        switch (testTransaction.get().getBodyCase()) {
            case BYTESTRANSACTION:
                handleBytesTransaction(testTransaction.get(), id, state);
                break;
            case FCMTRANSACTION:
                handleFCMTransaction(
                        testTransaction.get(), id, timestamp, checkIfSignatureIsInvalid(trans, state), state);
                break;
            case CONTROLTRANSACTION:
                handleControlTransaction(testTransaction.get(), id, timestamp, state);
                break;
            case FREEZETRANSACTION:
                handleFreezeTransaction(testTransaction.get(), state);
                break;
            case SIMPLEACTION:
                handleSimpleAction(testTransaction.get().getSimpleAction(), state);
                break;
            case VIRTUALMERKLETRANSACTION:
                handleVirtualMerkleTransaction(
                        testTransaction.get().getVirtualMerkleTransaction(), id, timeCreated, state);
                break;
            case STATESIGNATURETRANSACTION:
                consumeSystemTransaction(testTransaction.get(), id, semanticVersion, stateSignatureTransactionCallback);
                return;
            default:
                logger.error(EXCEPTION.getMarker(), "Unrecognized transaction!");
        }

        //////////// end timing/////////////
        final long htNetTime = System.nanoTime() - splitTime2 + (splitTime1 - startTime);
        if (testTransaction.get().hasFcmTransaction()) {
            final FCMTransaction fcmTransaction = testTransaction.get().getFcmTransaction();
            if (!fcmTransaction.hasActivity() && !fcmTransaction.hasDummyTransaction()) {
                switch (Objects.requireNonNull(FCMTransactionUtils.getEntityType(fcmTransaction))) {
                    case Crypto:
                        htFCMSumNano += htNetTime;
                        htCountFCM++;
                        htFCMAccounts = state.getFcmFamily().getMap().size();
                        break;
                    case FCQ:
                        htFCQSumNano += htNetTime;
                        htCountFCQ++;
                        htFCQAccounts = state.getFcmFamily().getAccountFCQMap().size();
                        break;
                }
            }
        }
    }

    private void consumeSystemTransaction(
            @NonNull final TestTransaction transaction,
            @NonNull final NodeId creator,
            @NonNull final SemanticVersion semanticVersion,
            @NonNull
                    final Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        final var stateSignatureTransaction =
                convertStateSignatureTransactionFromTestToSourceType(transaction.getStateSignatureTransaction());
        stateSignatureTransactionCallback.accept(
                new ScopedSystemTransaction<>(creator, semanticVersion, stateSignatureTransaction));
    }

    private StateSignatureTransaction convertStateSignatureTransactionFromTestToSourceType(
            final com.swirlds.demo.platform.fs.stresstest.proto.StateSignatureTransaction stateSignatureTransaction) {
        return StateSignatureTransaction.newBuilder()
                .round(stateSignatureTransaction.getRound())
                .signature(Bytes.wrap(stateSignatureTransaction.getSignature().toByteArray()))
                .hash(Bytes.wrap(stateSignatureTransaction.getHash().toByteArray()))
                .build();
    }

    /**
     * Do initial genesis setup.
     */
    private void genesisInit(PlatformTestingToolState state) {
        logger.info(LOGM_STARTUP, "Set QuorumResult from genesisInit()");
        state.setQuorumResult(
                new QuorumResult<>(platform.getRoster().rosterEntries().size()));

        state.setIssLeaf(new IssLeaf());
    }

    private MessageDigest createKeccakDigest() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("KECCAK-256");
        } catch (NoSuchAlgorithmException ignored) {
            try {
                digest = MessageDigest.getInstance("SHA3-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        return digest;
    }

    private Bytes keccak256(final Bytes bytes) {
        final MessageDigest keccakDigest = createKeccakDigest();
        bytes.writeTo(keccakDigest);
        return Bytes.wrap(keccakDigest.digest());
    }

    public static Bytes fromByteString(ByteString byteString) {
        return Bytes.wrap(byteString.toByteArray());
    }

    private void expandSignatures(
            final Transaction trans,
            final TestTransactionWrapper testTransactionWrapper,
            PlatformTestingToolState state) {
        if (state.getConfig().isAppendSig()) {
            final Bytes testTransactionRawBytes = fromByteString(testTransactionWrapper.getTestTransactionRawBytes());
            final Bytes publicKey = fromByteString(testTransactionWrapper.getPublicKeyRawBytes());
            final Bytes signature = fromByteString(testTransactionWrapper.getSignaturesRawBytes());
            final AppTransactionSignatureType AppSignatureType = testTransactionWrapper.getSignatureType();

            final SignatureType signatureType;
            Bytes signaturePayload = testTransactionRawBytes;

            if (AppSignatureType == AppTransactionSignatureType.ED25519) {
                signatureType = SignatureType.ED25519;
            } else if (AppSignatureType == AppTransactionSignatureType.ECDSA_SECP256K1) {
                signatureType = SignatureType.ECDSA_SECP256K1;
                signaturePayload = keccak256(testTransactionRawBytes);
            } else if (AppSignatureType == AppTransactionSignatureType.RSA) {
                signatureType = SignatureType.RSA;
            } else {
                throw new UnsupportedOperationException("Unknown application signature type " + AppSignatureType);
            }

            final TransactionSignature transactionSignature =
                    new TransactionSignature(signaturePayload, publicKey, signature, signatureType);
            trans.setMetadata(transactionSignature);

            CRYPTOGRAPHY.verifySync(transactionSignature);
        }
    }

    /**
     * Report current progress, if currentAmount equals multiple times of markerPercentage of expectedAmount For example
     * if expectedAmount = 88, markerPercentage = 20, the progress will be reported if current progress percentage is
     * 20%, 40%, 60%, 80%, 100%. Accordingly, currentAmount equals to 17, 35, 52, 70, and 88
     * <p>
     * If markerPercentage is 15, then should report progress at 15%, 30%, 45%, 60%, 75%, 90% and 100%
     */
    private void logProgress(
            @NonNull final NodeId id,
            final int markerPercentage,
            @NonNull final PAYLOAD_TYPE type,
            final long expectedAmount,
            final long currentAmount) {
        if (markerPercentage != 0 && currentAmount != 0 && expectedAmount != 0) {
            if (currentAmount == 1) {
                logger.info(LOGM_DEMO_INFO, "PlatformTestingDemo id {} HANDLE {} START", id, type);
            } else if (currentAmount == expectedAmount) {
                logger.info(LOGM_DEMO_INFO, "PlatformTestingDemo id {} HANDLE {} END", id, type);
            } else {
                final int reportTimes = (int) Math.ceil(((double) 100) / markerPercentage);
                for (int i = 1; i <= reportTimes; i++) { // check currentAmount match which marker value
                    final int percentage = Math.min(100, i * markerPercentage);
                    final long reportNumber = expectedAmount * percentage / 100;
                    if (currentAmount == reportNumber) {
                        logger.info(
                                LOGM_DEMO_INFO,
                                "PlatformTestingDemo id {} HANDLE {} {}% currentAmount {} ",
                                id,
                                type,
                                percentage,
                                currentAmount);
                    }
                }
            }
        }
    }

    /**
     * Validate signatures when appendSig is true, if signature status is INVALID set invalidSig flag
     *
     * @param trans Transaction whose signatures needs to be validated
     * @return boolean that shows if the signature status is INVALID
     */
    private static boolean validateSignatures(final Transaction trans) {
        // Verify signatures if appendSig is true
        boolean invalidSig = false;
        final TransactionSignature signature = trans.getMetadata();
        if (signature != null) {
            if (VerificationStatus.INVALID.equals(signature.getSignatureStatus())) {
                invalidSig = true;
            }
        }
        return invalidSig;
    }

    /**
     * Remove expired records from FCQs
     *
     * @param consensusCurrentTimestamp transaction records whose expiration time is below or equal to this consensus
     *                                  time will be removed
     * @return number of removed records
     */
    public long purgeExpiredRecords(PlatformTestingToolState state, final long consensusCurrentTimestamp) {
        lastPurgeTimestamp = consensusCurrentTimestamp;

        final long startTime = System.nanoTime();
        final long removedNum = removeExpiredRecordsInExpirationQueue(state, consensusCurrentTimestamp);

        if (removedNum > 0) {
            final long timeTakenMicro = (System.nanoTime() - startTime) / MICROSECONDS_TO_NANOSECONDS;
            logger.info(
                    LOGM_EXPIRATION,
                    "Finish removing expired records from FCQs. Has removed: {} in {} ms",
                    removedNum,
                    timeTakenMicro);
            htCountExpiration += removedNum;
            htFCQExpirationSumMicro += timeTakenMicro;
        }
        return removedNum;
    }

    private long removeExpiredRecordsInExpirationQueue(
            PlatformTestingToolState state, final long consensusCurrentTimestamp) {
        long removedNumOfRecords = state.removeExpiredRecordsInExpirationQueue(consensusCurrentTimestamp);
        htFCQRecordsCount = state.getExpirationQueue().size();
        return removedNumOfRecords;
    }

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull PlatformTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.forEachTransaction(v -> preHandleTransaction(v, event, state, stateSignatureTransactionCallback));
    }

    @Override
    public void onStateInitialized(
            @NonNull final PlatformTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        if (trigger == InitTrigger.RESTART) {
            state.rebuildExpectedMapFromState(Instant.EPOCH, true);
            state.rebuildExpirationQueue();
        }

        this.platform = platform;
        state.setSelfId(platform.getSelfId());
        UnsafeMutablePTTStateAccessor.getInstance().setMutableState(platform.getSelfId(), state);

        initialized.set(true);

        TransactionSubmitter.setForcePauseCanSubmitMore(new AtomicBoolean(false));

        // If parameter exists, load PayloadCfgSimple from top level json configuration file
        // Otherwise, load the default setting
        final String[] parameters = ParameterProvider.getInstance().getParameters();
        if (parameters != null && parameters.length > 0) {
            final String jsonFileName = parameters[0];
            final PayloadCfgSimple payloadCfgSimple = PlatformTestingToolMain.getPayloadCfgSimple(jsonFileName);
            state.setConfig(payloadCfgSimple);
        } else {
            state.setConfig(new PayloadCfgSimple());
        }

        state.getStateExpectedMap().setNodeId(platform.getSelfId().id());
        state.getStateExpectedMap().setWeightedNodeNum(RosterUtils.getNumberWithWeight(platform.getRoster()));

        // initialize data structures used for FCQueue transaction records expiration
        state.initializeExpirationQueueAndAccountsSet();
        logger.info(LOGM_STARTUP, () -> new SoftwareVersionPayload(
                        "Trigger and PreviousSoftwareVersion state received in init function",
                        trigger.toString(),
                        Objects.toString(previousVersion))
                .toString());

        if (trigger == InitTrigger.GENESIS) {
            genesisInit(state);
        }
        state.invalidateHash();
        TestingAppStateInitializer.DEFAULT.initStates(state);

        // compute hash
        try {
            platform.getContext().getMerkleCryptography().digestTreeAsync(state).get();
        } catch (final ExecutionException e) {
            logger.error(EXCEPTION.getMarker(), "Exception occurred during hashing", e);
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Interrupted while hashing state. Expect buggy behavior.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * For every 3 consensus rounds, seal the consensus round. If it's a freeze round, seal it regardless of the round
     * number.
     *
     * @param round the current consensus round
     * @param state the current state of the platform testing tool
     * @return {@code true} when the round should be sealed, {@code false} otherwise
     */
    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull PlatformTestingToolState state) {
        // if this is a freeze round, we need to seal it
        // we cannot check the freeze time in the state at this point, because lastFrozenTime has already been updated
        // so we remember the freeze round number in onHandleConsensusRound and check it here
        if (round.getRoundNum() == freezeRound) {
            return true;
        }
        return round.getRoundNum() % 3 == 0;
    }

    @Override
    public void onUpdateWeight(
            @NonNull PlatformTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull PlatformTestingToolState recoveredState) {
        // no-op
    }
}
