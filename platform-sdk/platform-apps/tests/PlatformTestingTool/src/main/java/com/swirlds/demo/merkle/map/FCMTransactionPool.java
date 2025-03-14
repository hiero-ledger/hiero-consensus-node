// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_DELETE;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_DELETE_FCQ_NODE;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_TRANSFER;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_TRANSFER_FCQ;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_UPDATE;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_UPDATE_FCQ;
import static com.swirlds.demo.platform.TransactionSubmitter.USE_DEFAULT_TPS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.Crypto;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.FCQ;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Transfer;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;

import com.google.protobuf.ByteString;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.FastCopyable;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.HotspotConfiguration;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.platform.PayloadConfig;
import com.swirlds.demo.platform.PttTransactionPool;
import com.swirlds.demo.platform.TransactionSubmitter;
import com.swirlds.demo.platform.Triple;
import com.swirlds.demo.platform.fs.stresstest.proto.Activity;
import com.swirlds.demo.platform.fs.stresstest.proto.AssortedAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.AssortedFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.BurnToken;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccountFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQNode;
import com.swirlds.demo.platform.fs.stresstest.proto.DummyTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTxType;
import com.swirlds.demo.platform.fs.stresstest.proto.FCQTxType;
import com.swirlds.demo.platform.fs.stresstest.proto.MintToken;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalance;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalanceFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferToken;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccountFCQ;
import com.swirlds.demo.platform.nft.NftId;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.system.Platform;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class FCMTransactionPool implements FastCopyable {

    /** This version number should be used to handle compatibility issues that may arise from any future changes */
    private static final long VERSION = 1;

    /**
     * use this for all logging
     */
    private static final Logger logger = LogManager.getLogger(FCMTransactionPool.class);

    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO");

    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");

    public static final Marker DEMO_TRANSACTION_INFO = MarkerManager.getMarker("DEMO_TRANSACTION_INFO");

    /**
     * the standard pseudo-random number generator
     */
    private Random random;

    private FCMConfig config;

    private PayloadConfig payloadConfig;

    private long myID;
    private Platform platform;

    private TransactionSubmitter submitter;

    ///////////////////////////////////////////
    // Copyable variables

    private int sequentialTypeIndex = 0;
    private long[] sequentialTestCount = null;
    private boolean doneWithGeneration = false;
    private PttTransactionPool parentPool;

    // Family of expectedMaps
    private final ExpectedFCMFamily expectedFCMFamily;
    private int loopCounter = 0;
    private Long assortedFCMEntityCount = 0L;
    private Long assortedFCQEntityCount = 0L;

    private PAYLOAD_TYPE generateType; // payload type going to be generated
    /**
     * The {@link FCMSequential} object corresponding to {@link #generateType}, or null if none exists.
     */
    private FCMSequential currentSequential;

    private int sizeAfterPadding;
    private Pair<FCMTransaction.Builder, MapKey> pair;

    private boolean immutable;

    private int accountSubsetFCM = 0;
    private int accountSubsetFCMFCQ = 0;
    private int tokenIdIndex = 0;

    /**
     * Constant to be used with getMapKeyForFCMTx indicating that no account subset restrictions are to be used
     */
    public static final int UNRESTRICTED_SUBSET = 0;

    /**
     * allow FCMTransactionHandler to get access to whatever account range subset
     * has been specified for FCM transactions (update, transfer)
     *
     * @return 0 means no restrictions on the range.  2 would mean only provide two
     * 		(deterministic) accounts distributed from the current active range.
     * 		(typically first and last for the 2 case) 3 would mean only provide three
     * 		first, middle, and last.  Etc
     */
    private int getAccountSubsetFCM() {
        return accountSubsetFCM;
    }

    private void setAccountSubsetFCM(final int accountSubsetFCM) {
        this.accountSubsetFCM = accountSubsetFCM;
    }

    /**
     * allow FCMTransactionHandler to get access to whatever account range subset
     * has been specified for FCMFCQ transactions (update, transfer)
     *
     * @return - 0 means no restrictions on the range.  2 would mean only provide two
     * 		(deterministic) accounts distributed from the current active range.
     * 		(typically first and last for the 2 case) 3 would mean only provide three
     * 		first, middle, and last.  Etc
     */
    private int getAccountSubsetFCMFCQ() {
        return accountSubsetFCMFCQ;
    }

    private void setAccountSubsetFCMFCQ(final int accountSubsetFCMFCQ) {
        this.accountSubsetFCMFCQ = accountSubsetFCMFCQ;
    }

    /**
     * Constructs a TransactionPool instance with a fixed pool size, fixed transaction size, and whether to pre-sign
     * each transaction.
     *
     * @throws IllegalArgumentException
     * 		if the {@code poolSize} or the {@code transactionSize} parameters are less than one (1)
     */
    public FCMTransactionPool(
            Platform platform,
            long myID,
            FCMConfig config,
            TransactionSubmitter submitter,
            PttTransactionPool parentPool,
            ExpectedFCMFamily expectedFCMFamily,
            PayloadConfig payloadConfig) {

        this.expectedFCMFamily = expectedFCMFamily;
        if (config == null) {
            return;
        }

        this.random = new Random();
        long seed = random.nextLong();
        this.random.setSeed(seed);
        logger.info(MARKER, "Random seed for FCMTransactionPool is {}", () -> seed);

        this.myID = myID;
        this.config = config;
        this.platform = platform;
        this.submitter = submitter;
        this.parentPool = parentPool;
        logger.info(DEMO_TRANSACTION_INFO, "Running on platform with id {}", () -> this.myID);
        if (config.isSequentialTest()) {
            sequentialTestCount = new long[config.getSequentials().length];
            logger.info(MARKER, "This test will generate sequential FCM payload:");
            for (int i = 0; i < config.getSequentials().length; i++) {
                logger.info(
                        DEMO_TRANSACTION_INFO,
                        "This test will generate FCM payload type {} for {} times",
                        config.getSequentialType(i),
                        config.getSequentialAmount(i));
            }
        }
        this.payloadConfig = payloadConfig;
    }

    /**
     * Retrieves a random transaction from the pool of pre-generated transactions.
     *
     * @return a random transaction from the pool
     */
    public Triple<byte[], PAYLOAD_TYPE, MapKey> getTransaction(final boolean invalidSig) {
        Pair<FCMTransaction, MapKey> transactionMapKeyPair = null;

        transactionMapKeyPair = getPayloadFromSequentialTest(invalidSig);

        if (transactionMapKeyPair == null) {
            return null;
        }

        PAYLOAD_TYPE payloadType = PAYLOAD_TYPE.BodyCase_TO_PAYLOAD_TYPE.get(
                transactionMapKeyPair.key().getBodyCase());
        Triple<byte[], PAYLOAD_TYPE, MapKey> rval = Triple.of(
                TestTransaction.newBuilder()
                        .setFcmTransaction(transactionMapKeyPair.key())
                        .build()
                        .toByteArray(),
                payloadType,
                transactionMapKeyPair.value());

        return rval;
    }

    private boolean isSkipStage() {
        if (config.getNodeList() != null) {
            ArrayList<Long> nodeList = config.getNodeList()[sequentialTypeIndex];
            if (nodeList.size() != 0 && (!nodeList.contains(myID))) {
                // skip to next stage
                PAYLOAD_TYPE skipType = config.getSequentialType(sequentialTypeIndex);
                logger.info(MARKER, "Skip transaction generation for node <{}> for type {}", myID, skipType);
                return true;
            }
        }
        return false;
    }

    private void setCustomizeTPS() {
        // only set customized TPS once at the beginning of each stage
        if (sequentialTestCount[this.sequentialTypeIndex] == 0) {
            if (config.getTpsList() != null) {
                ArrayList<Long> tpsList = config.getTpsList()[sequentialTypeIndex];
                for (int i = 0; i < tpsList.size(); i++) {
                    if (myID == i) {
                        submitter.setCustomizedTPS(tpsList.get(i));
                        return;
                    }
                }
            }
            // clear and TPS set in previous stage if tpsList is null or empty
            submitter.setCustomizedTPS(USE_DEFAULT_TPS);
        }
    }

    // get current TPS, payload type and padding zie
    private void getPayloadTypeAndSize() {
        setCustomizeTPS();
        generateType = config.getSequentialType(sequentialTypeIndex);
        if (sequentialTypeIndex < config.getSequentials().length) {
            currentSequential = config.getSequentials()[sequentialTypeIndex];
        } else {
            currentSequential = null;
        }
        sizeAfterPadding = config.getSequentialSize(sequentialTypeIndex);
        sequentialTestCount[this.sequentialTypeIndex]++; // counter increase, one more payload has been generated
    }

    private Pair<FCMTransaction, MapKey> getPayloadFromSequentialTest(final boolean invalidSig) {
        // ignore entries with sequentialAmount == 0, unless it's an ACCOUNT_SUBSET (FCM or FCQ) flag
        skipSequentialAmountZero();

        // check if the following stages need to be skipped
        while (sequentialTypeIndex < config.getSequentials().length && isSkipStage()) {
            sequentialTypeIndex++;
        }

        if (doneWithGeneration) {
            return null;
        }

        if (sequentialTypeIndex >= config.getSequentials().length) {
            logger.info(
                    MARKER,
                    "Generated enough FCM test for sequential mode, stop generating {} >= {}",
                    () -> sequentialTypeIndex,
                    () -> config.getSequentials().length);
            doneWithGeneration = true;
            return null; // null causes transaction generation to end
        }

        getPayloadTypeAndSize();
        // look for account range restriction types
        while ((generateType == PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET)
                || (generateType == PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET_FCQ)) {
            if (generateType == PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET) {
                setAccountSubsetFCM(config.getSequentialAmount(sequentialTypeIndex));
            }
            if (generateType == PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET_FCQ) {
                setAccountSubsetFCMFCQ(config.getSequentialAmount(sequentialTypeIndex));
            }
            // skip to next stage in sequentials
            sequentialTypeIndex++;
            getPayloadTypeAndSize();
        }

        if (generateType == PAYLOAD_TYPE.TYPE_TEST_SYNC) {
            sequentialTypeIndex++;

            skipInvalidStageAfterPause();
            int interval = sizeAfterPadding;

            //			if (generateType == PAYLOAD_TYPE.TYPE_TEST_SYNC) {
            //				submitter.setPauseUntilTime(-1);           //set as unlimited wait
            //				submitter.setSyncWaitTimeSecond(interval); //save wait time to be used later
            //			} else if (interval == -1) {
            //				submitter.setPauseUntilTime(-1);
            //			} else {
            //				submitter.setPauseUntilTime(System.currentTimeMillis() + interval * 1000);
            //			}

            submitter.sendTransaction(platform, parentPool.createControlTranBytes(ControlType.ENTER_SYNC));

            // check if the following stages need to be skipped
            while (sequentialTypeIndex < config.getSequentials().length && isSkipStage()) {
                sequentialTypeIndex++;
            }

            if (sequentialTypeIndex >= config.getSequentials().length) {
                logger.info(MARKER, "Generated enough FCM test for sequential mode");
                return null; // null causes transaction generation to end
            }
            getPayloadTypeAndSize();
        }

        if (generateType == PAYLOAD_TYPE.TYPE_TEST_LOOP
                && loopCounter < config.getSequentialAmount(sequentialTypeIndex)) {
            this.sequentialTypeIndex = 0;
            sequentialTestCount = new long[config.getSequentials().length];
            loopCounter++;
            getPayloadTypeAndSize();
            logger.info(MARKER, "TYPE_TEST_LOOP detected loop counter {}", loopCounter);
        }

        // move to next file payload type
        if (sequentialTestCount[this.sequentialTypeIndex] >= config.getSequentialAmount(sequentialTypeIndex)) {
            logger.info(MARKER, "Generated enough FCM transaction for type {}", generateType);
            sequentialTypeIndex++;
        }

        return getPayloadFromType(sizeAfterPadding, generateType, invalidSig);
    }

    /**
     * Generate Transaction from PAYLOAD_TYPE
     *
     * @param sizeAfterPadding
     * @param generateType
     * @param invalidSig
     * @return
     */
    private Pair<FCMTransaction, MapKey> getPayloadFromType(
            final int sizeAfterPadding, final PAYLOAD_TYPE generateType, final boolean invalidSig) {
        Pair<FCMTransaction.Builder, MapKey> builderMapKeyPair;

        checkAccountCounter(generateType);

        switch (generateType) {
            case TYPE_FCM_CREATE:
                builderMapKeyPair = this.generateFCMCreateAccountWithID(sizeAfterPadding);
                break;
            case TYPE_FCM_TRANSFER:
                builderMapKeyPair = this.generateFCMTransferBalanceWithAccountFrom(sizeAfterPadding);
                break;
            case TYPE_FCM_UPDATE:
                builderMapKeyPair = this.generateFCMUpdateAccountWithAccountID(sizeAfterPadding);
                break;
            case TYPE_FCM_DELETE:
                builderMapKeyPair = this.generateFCMDeleteAccountWithID(sizeAfterPadding);
                break;
            case TYPE_FCM_ASSORTED:
                builderMapKeyPair = this.generateFCMAssorted(sizeAfterPadding);
                break;
            case TYPE_FCM_ASSORTED_FCQ:
                builderMapKeyPair = this.generateFCMAssortedFCQ(sizeAfterPadding);
                break;
            case TYPE_FCM_CREATE_FCQ:
                builderMapKeyPair = this.generateFCMCreateAccountFCQWithId(sizeAfterPadding, false);
                break;
            case TYPE_FCM_CREATE_WITH_RECORDS_FCQ:
                builderMapKeyPair = this.generateFCMCreateAccountFCQWithId(sizeAfterPadding, true);
                break;
            case TYPE_FCM_TRANSFER_FCQ:
                builderMapKeyPair = this.generateFCMTransferFCQWithID(sizeAfterPadding);
                break;
            case TYPE_FCM_UPDATE_FCQ:
                builderMapKeyPair = this.generateFCMUpdateFCQ(sizeAfterPadding);
                break;
            case TYPE_FCM_DELETE_FCQ_NODE:
                builderMapKeyPair = this.generateFCMDeleteFCQNode();
                break;
            case TYPE_FCM_DELETE_FCQ:
                builderMapKeyPair = this.generateFCMDeleteFCQ();
                break;
            case TYPE_MINT_TOKEN:
                builderMapKeyPair = this.generateMintToken();
                break;
            case TYPE_TRANSFER_TOKEN:
                builderMapKeyPair = this.generateTransferToken();
                break;
            case TYPE_BURN_TOKEN:
                builderMapKeyPair = this.generateBurnToken();
                break;
            case TYPE_DUMMY_TRANSACTION:
                builderMapKeyPair = this.generateDummyTransaction();
                break;
            case SAVE_EXPECTED_MAP:
                // All nodes generate a SAVE_EXPECTED_MAP transaction, but the transaction is only handled by nodes if
                // it was created by node0.
                // The SAVE_EXPECTED_MAP transaction informs all nodes to save their expected maps to disk.
                builderMapKeyPair = this.generateActivity(Activity.ActivityType.SAVE_EXPECTED_MAP);
                logger.info(MARKER, "node{} submits a transaction SAVE_EXPECTED_MAP", myID);
                break;
            default:
                throw new RuntimeException(String.format("Invalid FCM operation: %s", generateType));
        }

        if (builderMapKeyPair == null) {
            return null;
        }
        FCMTransaction fcmTransaction = builderMapKeyPair
                .key()
                .setInvalidSig(invalidSig)
                .setOriginNode(myID)
                .build();

        return Pair.of(fcmTransaction, builderMapKeyPair.value());
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMDeleteFCQ() {
        MapKey key = getMapKeyForTx(Delete, FCQ);
        if (key == null) {
            return null;
        }

        DeleteFCQ deleteFCQ = DeleteFCQ.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .build();

        logger.trace(DEMO_TRANSACTION_INFO, "Deleting FCQ with key {}", () -> key);
        return Pair.of(FCMTransaction.newBuilder().setDeleteFCQ(deleteFCQ), key);
    }

    /**
     * get a MapKey for a FCMTransaction
     * if ExpectedMap is not null, use information in ExpectedMap to generate MapKey for Transaction;
     * else use TransactionCounter to get MapKey, as the way it used to do;
     *
     * @param txType
     * @param entityType
     * @return
     */
    private MapKey getMapKeyForTx(final TransactionType txType, final EntityType entityType) {
        // use information in ExpectedMap to generate MapKey for Transaction;
        // for update/transfer/delete/append transaction, returns an existing entity of the given type
        // for Create transaction, shardId and realmId would be myID
        if (txType.equals(Create)) {
            // 	and accountId would be total number of entities in expectedMap;
            return new MapKey(myID, myID, expectedFCMFamily.getNextIdToCreate());
        }
        // Update/Transfer only
        if (entityType.equals(Crypto)) {
            if (txType.equals(Transfer) || txType.equals(Update)) {
                return expectedFCMFamily.getMapKeyForFCMTx(
                        txType,
                        entityType,
                        payloadConfig.isPerformOnDeleted(),
                        payloadConfig.isOperateEntitiesOfSameNode(),
                        getAccountSubsetFCM());
            }
        } else if (entityType.equals(FCQ)) {
            if (txType.equals(Transfer) || txType.equals(Update)) {
                return expectedFCMFamily.getMapKeyForFCMTx(
                        txType,
                        entityType,
                        payloadConfig.isPerformOnDeleted(),
                        payloadConfig.isOperateEntitiesOfSameNode(),
                        getAccountSubsetFCMFCQ());
            }
        }
        // all other cases: account deletion, etc.
        return expectedFCMFamily.getMapKeyForFCMTx(
                txType,
                entityType,
                payloadConfig.isPerformOnDeleted(),
                payloadConfig.isOperateEntitiesOfSameNode(),
                UNRESTRICTED_SUBSET);
    }

    /**
     * Generate FCMTransaction with an activity, such as saving expected map
     *
     * @param type
     * @return
     */
    private Pair<FCMTransaction.Builder, MapKey> generateActivity(Activity.ActivityType type) {
        return Pair.of(
                FCMTransaction.newBuilder().setActivity(Activity.newBuilder().setType(type)), null);
    }

    /**
     * check corresponding accountCounter for given PAYLOAD_TYPE
     * when PAYLOAD_TYPE is not for creation and the accountCounter is zero, wait and try until accountCounter is
     * greater
     * than zero (because we need to generate accountNum for updating, transferring, or deleting, by dividing
     * transactionIndex by corresponding accountCounter).
     *
     * @param type
     */
    private void checkAccountCounter(PAYLOAD_TYPE type) {
        // for TYPE_FCM_DELETE, the accountNum is equal to deleteDataAccountIndex, so we don't need to check
        // accountCounter
        if (!type.name().startsWith("TYPE_FCM")
                || type.name().startsWith("TYPE_FCM_CREATE")
                || type.name().startsWith("TYPE_FCM_ASSORTED")
                || type.name().equals("TYPE_FCM_DELETE")) {
            return;
        }
        /**
         * Key: TxType;
         * Value: name of corresponding accountCounter
         */
        EntityType entityType;
        /**
         * for PAYLOAD_TYPE except FCM create type, when corresponding FCM accountCounter is zero, wait until it's
         * greater than zero
         */
        if (type.name().endsWith("FCQ")) {
            entityType = FCQ; // corresponds to fcqCounter
        } else {
            entityType = Crypto; // corresponds to cryptoCounter
        }

        expectedFCMFamily.waitWhileExpectedMapEmpty(entityType, type);
    }

    private void skipSequentialAmountZero() {
        while (sequentialTypeIndex < config.getSequentials().length
                && config.getSequentialAmount(sequentialTypeIndex) == 0) {
            final PAYLOAD_TYPE sequentialType = config.getSequentials()[sequentialTypeIndex].getSequentialType();
            // TYPE_FCM_ACCOUNT_SUBSET and TYPE_FCM_ACCOUNT_SUBSET_FCQ permit zeros, so don't skip
            if ((sequentialType == PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET)
                    || (sequentialType == PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET_FCQ)) {
                break;
            }
            logger.error(
                    EXCEPTION.getMarker(),
                    "Warning: sequentialAmount is 0 for {}, will be skipped ",
                    config.getSequentials()[sequentialTypeIndex]);
            sequentialTypeIndex++;
        }
    }

    /**
     * If next stage sequentialAmount is 0, or another pause stage, skip
     */
    private void skipInvalidStageAfterPause() {
        // use a local variable to check whether following stages payload have correct parameters
        // don't use generateType, since generateType has the current stage payload type and
        // still being used
        PAYLOAD_TYPE test_type = config.getSequentialType(sequentialTypeIndex);
        long amount = config.getSequentialAmount(sequentialTypeIndex);

        while (sequentialTypeIndex < config.getSequentials().length
                && (amount == 0 || test_type == PAYLOAD_TYPE.TYPE_TEST_SYNC)) {
            if (test_type == PAYLOAD_TYPE.TYPE_TEST_SYNC) {
                logger.error(
                        EXCEPTION.getMarker(), "Warning: A pause stage after previous pause stage, will be skipped ");
            } else {
                logger.error(
                        EXCEPTION.getMarker(), "Warning: sequentialAmount is 0 for {}, will be skipped ", test_type);
            }
            sequentialTypeIndex++;
            test_type = config.getSequentialType(sequentialTypeIndex); // update
            amount = config.getSequentialAmount(sequentialTypeIndex);
        }
    }

    private static final int STEP = 3;
    private static final int TRANSFER_AMOUNT = 1_000;
    private static final int INIT_BALANCE = 100_000;
    private static final int INIT_THRESHOLD = 10_000;

    private Pair<FCMTransaction.Builder, MapKey> generateFCMUpdateFCQ(final int contentSize) {
        MapKey key = getMapKeyForTx(Update, FCQ);
        if (key == null) {
            // when performOnDelete is false, getMapKeyForTx() might return null for this transaction
            // if all entities of this type have been deleted/expired
            return null;
        }
        final byte[] content = new byte[contentSize];
        this.random.nextBytes(content);
        final UpdateAccountFCQ updateAccountFCQ = UpdateAccountFCQ.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .setBalance(random.nextInt(TRANSFER_AMOUNT))
                .setIndex(1)
                .setContent(ByteString.copyFrom(content))
                .build();
        logger.trace(DEMO_TRANSACTION_INFO, "Updating FCQ with key {}", () -> key);
        modifyExpectedValue(key, Update);
        return Pair.of(FCMTransaction.newBuilder().setUpdateAccountFCQ(updateAccountFCQ), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMDeleteFCQNode() {
        MapKey key = getMapKeyForTx(Update, FCQ);
        if (key == null) {
            // when performOnDelete is false, getMapKeyForTx() might return null for this transaction
            // if all entities of this type have been deleted/expired
            return null;
        }
        final DeleteFCQNode delete = DeleteFCQNode.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .setIndex(0)
                .build();
        logger.trace(DEMO_TRANSACTION_INFO, "Deleting node from FCQ with key {}", () -> key);
        modifyExpectedValue(key, Update);
        return Pair.of(FCMTransaction.newBuilder().setDeleteFCQNode(delete), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMCreateAccountFCQWithId(
            final int contentSize, final boolean withRecords) {
        MapKey key = getMapKeyForTx(Create, FCQ);
        if (key == null) {
            // for create transaction, getMapKeyForTx() would never return null
            logger.error(EXCEPTION.getMarker(), "Failed to generate MapKey for CreateFCQ transaction");
            return null;
        }

        final byte[] content = new byte[contentSize];
        this.random.nextBytes(content);
        final long initialRecordNum = withRecords ? config.getInitialRecordNum() : 1;
        final CreateAccountFCQ createAccountFCQ = CreateAccountFCQ.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .setBalance(INIT_BALANCE + key.getAccountId())
                .setIndex(0)
                .setContent(ByteString.copyFrom(content))
                .setInitialRecordNum(initialRecordNum)
                .build();

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Creating FCQ with key {}, initialRecordNum: {}",
                () -> key,
                () -> initialRecordNum);
        addEntitiesToExpectedMap(key.getAccountId(), FCQ);
        return Pair.of(FCMTransaction.newBuilder().setCreateAccountFCQ(createAccountFCQ), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMTransferFCQWithID(final int contentSize) {
        MapKey fromKey = getMapKeyForTx(Transfer, FCQ);
        MapKey toKey = getMapKeyForTx(Transfer, FCQ);

        // two keys should be different
        while (fromKey != null && fromKey.equals(toKey)) {
            toKey = getMapKeyForTx(Transfer, FCQ);
        }

        if (fromKey == null || toKey == null) {
            // when performOnDelete is false, getMapKeyForTx() might return null for this transaction
            // if all entities of this type have been deleted/expired
            return null;
        }

        final byte[] fromContent = new byte[contentSize];
        final byte[] toContent = new byte[contentSize];
        this.random.nextBytes(fromContent);
        this.random.nextBytes(toContent);
        final TransferBalanceFCQ transfer = TransferBalanceFCQ.newBuilder()
                .setFromShardID(fromKey.getShardId())
                .setFromRealmID(fromKey.getRealmId())
                .setFromAccountID(fromKey.getAccountId())
                .setToShardID(toKey.getShardId())
                .setToRealmID(toKey.getRealmId())
                .setToAccountID(toKey.getAccountId())
                .setTransferAmount(TRANSFER_AMOUNT)
                .setNewFromContent(ByteString.copyFrom(fromContent))
                .setNewToContent(ByteString.copyFrom(toContent))
                .build();

        final MapKey toKeyFinal = toKey;
        logger.trace(DEMO_TRANSACTION_INFO, "Transferring FCQ with key {} and {}", () -> fromKey, () -> toKeyFinal);
        modifyExpectedValue(fromKey, Transfer);
        return Pair.of(FCMTransaction.newBuilder().setTransferBalanceFCQ(transfer), fromKey);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateTransferToken() {
        final MapKey key = getMapKeyForTx(Update, Crypto);
        if (key == null) {
            logger.error(EXCEPTION.getMarker(), "Failed to generate MapKey for transaction transferToken");
            return null;
        }

        final Optional<NftId> nftIdCheck = this.expectedFCMFamily.getAnyNftId();
        if (nftIdCheck.isEmpty()) {
            logger.error(EXCEPTION.getMarker(), "Failed to generate TokenId for transaction TransferToken");
            return null;
        }

        final NftId nftId = nftIdCheck.get();

        final TransferToken transferToken = TransferToken.newBuilder()
                .setTokenRealmId(nftId.getRealmNum())
                .setTokenShardId(nftId.getShardNum())
                .setTokenId(nftId.getTokenNum())
                .setToRealmId(key.getRealmId())
                .setToShardId(key.getShardId())
                .setToAccountId(key.getAccountId())
                .build();

        return Pair.of(FCMTransaction.newBuilder().setTransferToken(transferToken), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateDummyTransaction() {
        final DummyTransaction dummyTransaction = DummyTransaction.newBuilder().build();
        return Pair.of(FCMTransaction.newBuilder().setDummyTransaction(dummyTransaction), null);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateBurnToken() {
        final Optional<NftId> nftIdCheck = this.expectedFCMFamily.getAnyNftId();
        if (nftIdCheck.isEmpty()) {
            logger.error(EXCEPTION.getMarker(), "Failed to generate TokenId for transaction BurnToken");
            return null;
        }

        final NftId nftId = nftIdCheck.get();
        final BurnToken burnToken = BurnToken.newBuilder()
                .setTokenRealmId(nftId.getRealmNum())
                .setTokenShardId(nftId.getShardNum())
                .setTokenId(nftId.getTokenNum())
                .build();

        return Pair.of(FCMTransaction.newBuilder().setBurnToken(burnToken), null);
    }

    /**
     * Utility function for choosing which account will own a newly minted NFT.
     */
    private MapKey getNewTokenOwner() {

        if (currentSequential == null) {
            throw new IllegalStateException("The mint token operation must have a non-null sequential");
        }

        final HotspotConfiguration hotspotConfiguration = currentSequential.getHotspot();
        if (hotspotConfiguration != null) {
            final double choice = random.nextDouble();
            if (choice < hotspotConfiguration.getFrequency()) {
                final MapKey key = expectedFCMFamily.getMapKeyForFCMTx(
                        TransactionType.MintToken, Crypto, false, false, hotspotConfiguration.getSize());

                return key;
            }
        }

        return getMapKeyForTx(TransactionType.MintToken, Crypto);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateMintToken() {
        final MapKey key = getNewTokenOwner();

        if (key == null) {
            logger.error(EXCEPTION.getMarker(), "Failed to generate MapKey for MintToken transaction");
            return null;
        }

        final byte[] serialNumber = new byte[10];
        final byte[] memo = new byte[10];
        final byte[] symbol = new byte[10];

        random.nextBytes(serialNumber);
        random.nextBytes(memo);
        random.nextBytes(symbol);

        final MintToken mintToken = MintToken.newBuilder()
                .setAccountId(key.getAccountId())
                .setShardId(key.getShardId())
                .setRealmId(key.getRealmId())
                .setTokenRealmId(this.myID)
                .setTokenShardId(this.myID)
                .setTokenId(this.tokenIdIndex++)
                .setSerialNumber(new String(serialNumber))
                .setMemo(new String(memo))
                .build();

        return Pair.of(FCMTransaction.newBuilder().setMintToken(mintToken), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMCreateAccountWithID(final int sizeAfterPading) {
        MapKey key = getMapKeyForTx(Create, Crypto);
        if (key == null) {
            // for create transaction, getMapKeyForTx() would never return null
            logger.error(EXCEPTION.getMarker(), "Failed to generate MapKey for CreateAccount transaction");
            return null;
        }
        final CreateAccount createAccount = CreateAccount.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .setBalance(INIT_BALANCE + key.getAccountId())
                .setSendThreshold(INIT_THRESHOLD + key.getAccountId())
                .setReceiveThreshold(INIT_THRESHOLD + key.getAccountId())
                .setRequireSignature(false)
                .setUid(random.nextLong())
                .build();

        addEntitiesToExpectedMap(key.getAccountId(), Crypto);
        logger.trace(DEMO_TRANSACTION_INFO, "Creating account with key {}", () -> key);
        int currentLen = createAccount.toByteArray().length;
        if (currentLen < sizeAfterPading) {
            byte[] paddingBytes = new byte[sizeAfterPading - currentLen];
            return Pair.of(
                    FCMTransaction.newBuilder()
                            .setCreateAccount(createAccount)
                            .setPaddingBytes(ByteString.copyFrom(paddingBytes)),
                    key);
        }
        return Pair.of(FCMTransaction.newBuilder().setCreateAccount(createAccount), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMTransferBalanceWithAccountFrom(int sizeAfterPading) {
        final MapKey fromKey = getMapKeyForTx(Transfer, Crypto);
        MapKey toKey = getMapKeyForTx(Transfer, Crypto);
        // two keys should be different
        while (fromKey != null && fromKey.equals(toKey)) {
            toKey = getMapKeyForTx(Transfer, Crypto);
        }

        if (fromKey == null || toKey == null) {
            // when performOnDelete is false, getMapKeyForTx() might return null for this transaction
            // if all entities of this type have been deleted/expired
            return null;
        }

        TransferBalance transferBalance = TransferBalance.newBuilder()
                .setFromShardID(fromKey.getShardId())
                .setFromRealmID(fromKey.getRealmId())
                .setFromAccountID(fromKey.getAccountId())
                .setToShardID(toKey.getShardId())
                .setToRealmID(toKey.getRealmId())
                .setToAccountID(toKey.getAccountId())
                .setTransferAmount(TRANSFER_AMOUNT)
                .build();

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Transferring CryptoAccount with key ({},{},{}) and ({},{},{})",
                myID,
                myID,
                fromKey.getAccountId(),
                myID,
                myID,
                toKey.getAccountId());
        modifyExpectedValue(fromKey, Transfer);
        modifyExpectedValue(toKey, Transfer);

        int currentLen = transferBalance.toByteArray().length;
        if (currentLen < sizeAfterPading) {
            byte[] paddingBytes = new byte[sizeAfterPading - currentLen];
            return Pair.of(
                    FCMTransaction.newBuilder()
                            .setTransferBalance(transferBalance)
                            .setPaddingBytes(ByteString.copyFrom(paddingBytes)),
                    fromKey);
        }
        return Pair.of(FCMTransaction.newBuilder().setTransferBalance(transferBalance), fromKey);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMUpdateAccountWithAccountID(final int sizeAfterPading) {
        MapKey key = getMapKeyForTx(Update, Crypto);
        if (key == null) {
            // when performOnDelete is false, getMapKeyForTx() might return null for this transaction
            // if all entities of this type have been deleted/expired
            return null;
        }
        UpdateAccount updateAccount = UpdateAccount.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .setBalance((INIT_BALANCE + key.getAccountId()) * 2)
                .setSendThreshold((INIT_THRESHOLD + key.getAccountId()) * 2)
                .setReceiveThreshold((INIT_THRESHOLD + key.getAccountId()) * 2)
                .setRequireSignature(false)
                .build();

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Updating account with key ({},{},{})",
                () -> myID,
                () -> myID,
                () -> key.getAccountId());
        modifyExpectedValue(key, Update);

        int currentLen = updateAccount.toByteArray().length;
        if (currentLen < sizeAfterPading) {
            byte[] paddingBytes = new byte[sizeAfterPading - currentLen];
            return Pair.of(
                    FCMTransaction.newBuilder()
                            .setUpdateAccount(updateAccount)
                            .setPaddingBytes(ByteString.copyFrom(paddingBytes)),
                    key);
        }
        return Pair.of(FCMTransaction.newBuilder().setUpdateAccount(updateAccount), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMDeleteAccountWithID(final int sizeAfterPading) {
        MapKey key = getMapKeyForTx(Delete, Crypto);
        if (key == null) {
            // when performOnDelete is false, getMapKeyForTx() might return null for this transaction
            // if all entities of this type have been deleted/expired
            return null;
        }
        DeleteAccount deleteAccount = DeleteAccount.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .build();

        logger.trace(DEMO_TRANSACTION_INFO, "Deleting account with key {}", () -> key);
        modifyExpectedValue(key, Delete);

        int currentLen = deleteAccount.toByteArray().length;
        if (currentLen < sizeAfterPading) {
            byte[] paddingBytes = new byte[sizeAfterPading - currentLen];
            return Pair.of(
                    FCMTransaction.newBuilder()
                            .setDeleteAccount(deleteAccount)
                            .setPaddingBytes(ByteString.copyFrom(paddingBytes)),
                    key);
        }

        return Pair.of(FCMTransaction.newBuilder().setDeleteAccount(deleteAccount), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMAssorted(final int sizeAfterPadding) {

        AssortedAccount assortedAccount = null;
        MapKey key = null;
        MapKey keyTo;

        // if less than two objects exist,
        if (assortedFCMEntityCount > 2) {

            // generate transaction randomly from FCM account category
            switch (Objects.requireNonNull(FCMTxType.forNumber(random.nextInt(4)))) {
                case Update:
                    checkAccountCounter(TYPE_FCM_UPDATE);
                    key = getMapKeyForTx(Update, Crypto);

                    if (key != null) {
                        assortedAccount = AssortedAccount.newBuilder()
                                .setShardID(key.getShardId())
                                .setRealmID(key.getRealmId())
                                .setAccountID(key.getAccountId())
                                .setAmountBalance((INIT_BALANCE + key.getAccountId()) * 2)
                                .setSendThreshold((INIT_THRESHOLD + key.getAccountId()) * 2)
                                .setReceiveThreshold((INIT_THRESHOLD + key.getAccountId()) * 2)
                                .setRequireSignature(false)
                                .setTxType(FCMTxType.Update)
                                .build();
                        modifyExpectedValue(key, Update);
                    }
                    break;
                case Transfer:
                    checkAccountCounter(TYPE_FCM_TRANSFER);
                    key = getMapKeyForTx(Transfer, Crypto);
                    keyTo = getMapKeyForTx(Transfer, Crypto);

                    if (key != null) {
                        while (key != null && key.equals(keyTo)) {
                            keyTo = getMapKeyForTx(Transfer, Crypto);
                        }
                        if (keyTo != null) {
                            assortedAccount = AssortedAccount.newBuilder()
                                    .setShardID(key.getShardId())
                                    .setRealmID(key.getRealmId())
                                    .setAccountID(key.getAccountId())
                                    .setShardIdTo(keyTo.getShardId())
                                    .setRealmIdTo(keyTo.getRealmId())
                                    .setAccountIdTo(keyTo.getAccountId())
                                    .setAmountBalance(TRANSFER_AMOUNT)
                                    .setTxType(FCMTxType.Transfer)
                                    .build();
                            modifyExpectedValue(key, Transfer);
                            modifyExpectedValue(keyTo, Transfer);
                        } else {
                            // no key available for to so set to null and force a create
                            key = null;
                        }
                    }
                    break;
                case Delete:
                    checkAccountCounter(TYPE_FCM_DELETE);
                    key = getMapKeyForTx(Delete, Crypto);

                    if (key != null) {
                        assortedAccount = AssortedAccount.newBuilder()
                                .setShardID(key.getShardId())
                                .setRealmID(key.getRealmId())
                                .setAccountID(key.getAccountId())
                                .setTxType(FCMTxType.Delete)
                                .build();
                        modifyExpectedValue(key, Delete);

                        assortedFCMEntityCount--;
                    }
                    break;
                case Create:
                default:
                    // fallthrough to create
                    break;
            }
        }

        // if no key was available for Update, Transfer, Delete then create
        if (key == null) {
            key = getMapKeyForTx(Create, Crypto);

            assortedAccount = AssortedAccount.newBuilder()
                    .setShardID(key.getShardId())
                    .setRealmID(key.getRealmId())
                    .setAccountID(key.getAccountId())
                    .setAmountBalance(INIT_BALANCE + key.getAccountId())
                    .setSendThreshold(INIT_THRESHOLD + key.getAccountId())
                    .setReceiveThreshold(INIT_THRESHOLD + key.getAccountId())
                    .setTxType(FCMTxType.Create)
                    .build();

            addEntitiesToExpectedMap(key.getAccountId(), Crypto);
            assortedFCMEntityCount++;
        }

        logger.info(
                DEMO_TRANSACTION_INFO,
                "assorted fcm transaction {} for key {}",
                assortedAccount.getTxType().name(),
                key);

        return Pair.of(FCMTransaction.newBuilder().setAssortedAccount(assortedAccount), key);
    }

    private Pair<FCMTransaction.Builder, MapKey> generateFCMAssortedFCQ(final int contentSize) {

        AssortedFCQ assortedFCQ = null;
        MapKey key = null;
        MapKey keyTo;

        // if less than two objects exist,
        if (assortedFCQEntityCount > 2) {
            // generate transaction randomly from FCM account category
            switch (Objects.requireNonNull(FCQTxType.forNumber(random.nextInt(4)))) {
                case FCQUpdate:
                    checkAccountCounter(TYPE_FCM_UPDATE_FCQ);
                    key = getMapKeyForTx(Update, FCQ);

                    final byte[] content = new byte[contentSize];
                    random.nextBytes(content);
                    if (key != null) {
                        assortedFCQ = AssortedFCQ.newBuilder()
                                .setTxType(FCQTxType.FCQUpdate)
                                .setShardID(key.getShardId())
                                .setRealmID(key.getRealmId())
                                .setAccountID(key.getAccountId())
                                .setAmountBalance(random.nextInt(TRANSFER_AMOUNT))
                                .setIndex(1)
                                .setContent(ByteString.copyFrom(content))
                                .build();
                        modifyExpectedValue(key, Update);
                    }
                    break;
                case FCQTransfer:
                    checkAccountCounter(TYPE_FCM_TRANSFER_FCQ);
                    key = getMapKeyForTx(Transfer, FCQ);
                    keyTo = getMapKeyForTx(Transfer, FCQ);

                    if (key != null) {
                        while (key != null && key.equals(keyTo)) {
                            keyTo = getMapKeyForTx(Transfer, FCQ);
                        }
                        if (keyTo != null) {
                            final byte[] fromContent = new byte[contentSize];
                            final byte[] toContent = new byte[contentSize];
                            this.random.nextBytes(fromContent);
                            this.random.nextBytes(toContent);
                            assortedFCQ = AssortedFCQ.newBuilder()
                                    .setTxType(FCQTxType.FCQTransfer)
                                    .setShardID(key.getShardId())
                                    .setRealmID(key.getRealmId())
                                    .setAccountID(key.getAccountId())
                                    .setToShardID(keyTo.getShardId())
                                    .setToRealmID(keyTo.getRealmId())
                                    .setToAccountID(keyTo.getAccountId())
                                    .setAmountBalance(TRANSFER_AMOUNT)
                                    .setContent(ByteString.copyFrom(fromContent))
                                    .setNewToContent(ByteString.copyFrom(toContent))
                                    .build();
                            modifyExpectedValue(key, Transfer);
                        } else {
                            // no key available for to so set to null and force a create
                            key = null;
                        }
                    }
                    break;
                case FCQDelete:
                    checkAccountCounter(TYPE_FCM_DELETE_FCQ_NODE);
                    key = getMapKeyForTx(Delete, FCQ);

                    if (key != null) {
                        assortedFCQ = AssortedFCQ.newBuilder()
                                .setTxType(FCQTxType.FCQDelete)
                                .setShardID(key.getShardId())
                                .setRealmID(key.getRealmId())
                                .setAccountID(key.getAccountId())
                                .build();
                        modifyExpectedValue(key, Delete);

                        assortedFCQEntityCount--;
                    }
                    break;
                case FCQCreate:
                default:
                    // fallthrough to create
                    break;
            }
        }

        // if no key was available for Update, Transfer, Delete then create
        if (key == null) {
            key = getMapKeyForTx(Create, FCQ);

            final byte[] content = new byte[contentSize];
            this.random.nextBytes(content);
            final boolean withRecords = false; // this.random.nextBoolean();
            final long initialRecordNum = withRecords ? config.getInitialRecordNum() : 1;
            assortedFCQ = AssortedFCQ.newBuilder()
                    .setTxType(FCQTxType.FCQCreate)
                    .setShardID(key.getShardId())
                    .setRealmID(key.getRealmId())
                    .setAccountID(key.getAccountId())
                    .setAmountBalance(INIT_BALANCE + key.getAccountId())
                    .setIndex(0)
                    .setContent(ByteString.copyFrom(content))
                    .setInitialRecordNum(initialRecordNum)
                    .build();

            addEntitiesToExpectedMap(key.getAccountId(), FCQ);
            assortedFCQEntityCount++;
        }

        logger.info(
                DEMO_TRANSACTION_INFO,
                "assorted FCQ transaction {} for key {}",
                assortedFCQ.getTxType().name(),
                key);

        return Pair.of(FCMTransaction.newBuilder().setAssortedFCQ(assortedFCQ), key);
    }

    // Modify the value of an expectedMap if the key exists
    // This is done when a new transaction is submitted for the same key
    private void modifyExpectedValue(MapKey key, TransactionType transType) {
        ExpectedValue value = expectedFCMFamily.getExpectedMap().get(key);
        if (value != null) {
            value.setLatestSubmitStatus(new LifecycleStatus(
                    TransactionState.INITIALIZED, transType, Instant.now().getEpochSecond(), myID));
            expectedFCMFamily.getExpectedMap().put(key, value);
        }
    }

    // Add different entities to expectedMap
    private void addEntitiesToExpectedMap(long accountID, EntityType entityType) {
        MapKey key = new MapKey(myID, myID, accountID);
        ExpectedValue value = new ExpectedValue(
                entityType,
                new LifecycleStatus(
                        TransactionState.INITIALIZED, Create, Instant.now().getEpochSecond(), myID));
        expectedFCMFamily.addEntityToExpectedMap(key, value);
    }

    @Override
    public FCMTransactionPool copy() {
        throwIfImmutable();
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return this.immutable;
    }
}
