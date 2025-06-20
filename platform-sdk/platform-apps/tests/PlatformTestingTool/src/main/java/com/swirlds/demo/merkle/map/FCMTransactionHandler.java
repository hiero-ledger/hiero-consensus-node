// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_NANOSECONDS;
import static com.swirlds.demo.merkle.map.FCMTransactionPool.DEMO_TRANSACTION_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.HANDLED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Transfer;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.platform.PlatformTestingToolState;
import com.swirlds.demo.platform.expiration.ExpirationRecordEntry;
import com.swirlds.demo.platform.expiration.ExpirationUtils;
import com.swirlds.demo.platform.fs.stresstest.proto.AssortedAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.AssortedFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.BurnToken;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccountFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQNode;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.MintToken;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalance;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalanceFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferToken;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccountFCQ;
import com.swirlds.demo.platform.nft.Nft;
import com.swirlds.demo.platform.nft.NftId;
import com.swirlds.demo.platform.nft.NftLedger;
import com.swirlds.demo.platform.nft.ReferenceNftLedger;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.MapValue;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class FCMTransactionHandler {

    private static final Logger logger = LogManager.getLogger(FCMTransactionHandler.class);
    private static final Marker DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker DEMO_HANDLE_TX_TIME = MarkerManager.getMarker("DEMO_HANDLE_TX_TIME");
    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");
    /**
     * write to log when time taken for handling a transaction is longer than this value(in ms)
     */
    private static final int HANDLE_LOG_DURATION = 1000;

    /**
     * Dispatch FCM/FCQ transaction
     */
    public static void performOperation(
            final FCMTransaction fcmTransaction,
            final PlatformTestingToolState state,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords)
            throws IOException {
        final long startTime = System.nanoTime();
        PAYLOAD_TYPE type = null;

        final FCMFamily fcmFamily = state.getFcmFamily();

        if (fcmFamily == null) {
            logger.info(DEMO_INFO, "FCMFamily is null");
            return;
        }
        if (fcmTransaction.hasCreateAccount()) {
            type = PAYLOAD_TYPE.TYPE_FCM_CREATE;
            performCreateAccount(
                    fcmTransaction.getCreateAccount(),
                    fcmFamily.getMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasTransferBalance()) {
            type = PAYLOAD_TYPE.TYPE_FCM_TRANSFER;
            performTransferBetweenAccounts(
                    fcmTransaction.getTransferBalance(),
                    fcmFamily.getMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasDeleteAccount()) {
            type = PAYLOAD_TYPE.TYPE_FCM_DELETE;
            performDeleteAccount(
                    fcmTransaction.getDeleteAccount(),
                    fcmFamily.getMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasUpdateAccount()) {
            type = PAYLOAD_TYPE.TYPE_FCM_UPDATE;
            performUpdateAccount(
                    fcmTransaction.getUpdateAccount(),
                    fcmFamily.getMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasAssortedAccount()) {
            type = PAYLOAD_TYPE.TYPE_FCM_ASSORTED;
            performAssortedAccount(
                    fcmTransaction.getAssortedAccount(),
                    fcmFamily.getMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasAssortedFCQ()) {
            type = PAYLOAD_TYPE.TYPE_FCM_ASSORTED_FCQ;
            performAssortedFCQ(
                    fcmTransaction.getAssortedFCQ(),
                    fcmFamily.getAccountFCQMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType,
                    expirationTime,
                    expirationQueue,
                    accountsWithExpiringRecords);
        } else if (fcmTransaction.hasCreateAccountFCQ()) {
            type = PAYLOAD_TYPE.TYPE_FCM_CREATE_FCQ;
            performCreateAccountFCQ(
                    fcmTransaction.getCreateAccountFCQ(),
                    fcmFamily.getAccountFCQMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType,
                    expirationTime,
                    expirationQueue,
                    accountsWithExpiringRecords);
        } else if (fcmTransaction.hasDeleteFCQNode()) {
            type = PAYLOAD_TYPE.TYPE_FCM_DELETE_FCQ_NODE;
            performDeleteFCQNode(
                    fcmTransaction.getDeleteFCQNode(),
                    fcmFamily.getAccountFCQMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasDeleteFCQ()) {
            type = PAYLOAD_TYPE.TYPE_FCM_DELETE_FCQ;
            performDeleteFCQ(
                    fcmTransaction.getDeleteFCQ(),
                    fcmFamily.getAccountFCQMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType);
        } else if (fcmTransaction.hasUpdateAccountFCQ()) {
            type = PAYLOAD_TYPE.TYPE_FCM_UPDATE_FCQ;
            performUpdateAccountFCQ(
                    fcmTransaction.getUpdateAccountFCQ(),
                    fcmFamily.getAccountFCQMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType,
                    expirationTime,
                    expirationQueue,
                    accountsWithExpiringRecords);
        } else if (fcmTransaction.hasTransferBalanceFCQ()) {
            type = PAYLOAD_TYPE.TYPE_FCM_TRANSFER_FCQ;
            performTransferFCQ(
                    fcmTransaction.getTransferBalanceFCQ(),
                    fcmFamily.getAccountFCQMap(),
                    expectedFCMFamily,
                    originId,
                    timestamp,
                    entityType,
                    expirationTime,
                    expirationQueue,
                    accountsWithExpiringRecords);
        } else if (fcmTransaction.hasMintToken()) {
            type = PAYLOAD_TYPE.TYPE_MINT_TOKEN;
            performMintToken(
                    fcmTransaction.getMintToken(),
                    state.getNftLedger(),
                    state.getReferenceNftLedger(),
                    expectedFCMFamily);
        } else if (fcmTransaction.hasTransferToken()) {
            type = PAYLOAD_TYPE.TYPE_TRANSFER_TOKEN;
            performTransferToken(
                    fcmTransaction.getTransferToken(),
                    state.getNftLedger(),
                    state.getReferenceNftLedger(),
                    expectedFCMFamily);
        } else if (fcmTransaction.hasBurnToken()) {
            type = PAYLOAD_TYPE.TYPE_BURN_TOKEN;
            performBurnToken(
                    fcmTransaction.getBurnToken(),
                    state.getNftLedger(),
                    state.getReferenceNftLedger(),
                    expectedFCMFamily);
        }

        if (type != null) {
            final long timeTakenMs = (System.nanoTime() - startTime) / MILLISECONDS_TO_NANOSECONDS;
            if (timeTakenMs > HANDLE_LOG_DURATION) {
                logger.info(
                        DEMO_HANDLE_TX_TIME,
                        "Time taken for handling {} is greater than {}ms: {} ms",
                        type,
                        HANDLE_LOG_DURATION,
                        timeTakenMs);
            }
        }
    }

    private static void performMintToken(
            final MintToken mintToken,
            final NftLedger nftLedger,
            final ReferenceNftLedger referenceNftLedger,
            final ExpectedFCMFamily expectedFCMFamily) {

        final MapKey key = new MapKey(mintToken.getShardId(), mintToken.getRealmId(), mintToken.getAccountId());
        final NftId nftId = new NftId(mintToken.getTokenShardId(), mintToken.getTokenRealmId(), mintToken.getTokenId());

        final Nft token = new Nft();

        token.setMemo(mintToken.getMemo());
        token.setSerialNumber(mintToken.getSerialNumber());

        token.setShardNum(mintToken.getTokenShardId());
        token.setRealmNum(mintToken.getTokenRealmId());
        token.setTokenNum(mintToken.getTokenId());

        token.setMapKey(key);

        nftLedger.mintToken(key, nftId, token);
        expectedFCMFamily.addNftId(nftId);

        if (referenceNftLedger.isTokenTracked(nftId)) {
            referenceNftLedger.mintToken(key.copy(), nftId, token.deepCopy());
        }
    }

    private static void performBurnToken(
            final BurnToken burnToken,
            final NftLedger nftLedger,
            final ReferenceNftLedger referenceNftLedger,
            final ExpectedFCMFamily expectedFCMFamily) {

        final NftId nftId = new NftId(burnToken.getTokenShardId(), burnToken.getTokenRealmId(), burnToken.getTokenId());

        if (!expectedFCMFamily.doesTokenWithIdExist(nftId)) {
            // The requested token no longer exists.
            return;
        }

        nftLedger.burnToken(nftId);
        expectedFCMFamily.removeNftid(nftId);

        if (referenceNftLedger.isTokenTracked(nftId)) {
            referenceNftLedger.burnToken(nftId);
        }
    }

    private static void performTransferToken(
            final TransferToken transferToken,
            final NftLedger nftLedger,
            final ReferenceNftLedger referenceNftLedger,
            final ExpectedFCMFamily expectedFCMFamily) {
        final MapKey toKey =
                new MapKey(transferToken.getToShardId(), transferToken.getToRealmId(), transferToken.getToAccountId());

        final NftId nftId =
                new NftId(transferToken.getTokenShardId(), transferToken.getTokenRealmId(), transferToken.getTokenId());

        if (!expectedFCMFamily.doesTokenWithIdExist(nftId)) {
            // The requested token no longer exists.
            return;
        }

        nftLedger.transferToken(nftId, toKey);

        if (referenceNftLedger.isTokenTracked(nftId)) {
            referenceNftLedger.transferToken(nftId, toKey.copy());
        }
    }

    private static void performCreateAccount(
            final CreateAccount createAccount,
            final MerkleMap<MapKey, MapValueData> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType) {
        if (map == null) {
            logger.error(ERROR, "accountMap is null");
            return;
        }

        final MapKey key =
                new MapKey(createAccount.getShardID(), createAccount.getRealmID(), createAccount.getAccountID());
        final MapValueData value = createAccount(createAccount);
        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing create transaction for key {} with root hash {}, originId: {}",
                () -> key,
                map::getRootHash,
                () -> originId);
        map.put(key, value);

        // If handled successfully, update expectedValue's Hash,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, value, HANDLED, Create, timestamp, originId, false);
    }

    private static void performTransferBetweenAccounts(
            final TransferBalance transferBalance,
            final MerkleMap<MapKey, MapValueData> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType) {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountMap is null");
            return;
        }

        final MapKey fromKey = new MapKey(
                transferBalance.getFromShardID(), transferBalance.getFromRealmID(), transferBalance.getFromAccountID());
        final MapKey toKey = new MapKey(
                transferBalance.getToShardID(), transferBalance.getToRealmID(), transferBalance.getToAccountID());
        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing transfer transaction for keys {} and {} with root hash {}, originId: {}",
                () -> fromKey,
                () -> toKey,
                map::getRootHash,
                () -> originId);

        final long balance = transferBalance.getTransferAmount();
        final Pair<MapValueData, MapValueData> newValuePair =
                performTransfer(balance, fromKey, toKey, map, expectedFCMFamily);

        // If handled successfully, update expectedValue's Hash,
        // and set latestHandledStatus
        if (newValuePair == null) {
            return;
        }

        expectedFCMFamily.setLatestHandledStatusForKey(
                fromKey, entityType, newValuePair.left(), HANDLED, Transfer, timestamp, originId, false);

        expectedFCMFamily.setLatestHandledStatusForKey(
                toKey, entityType, newValuePair.right(), HANDLED, Transfer, timestamp, originId, false);
    }

    /**
     * perform Transfer, return a pair of new MapValueData of sender and recipient
     */
    private static Pair<MapValueData, MapValueData> performTransfer(
            final long balance,
            final MapKey fromKey,
            final MapKey toKey,
            final MerkleMap<MapKey, MapValueData> map,
            final ExpectedFCMFamily expectedMap) {
        if (entityMissingFromMap(fromKey, map, expectedMap)) {
            return null;
        }
        if (entityMissingFromMap(toKey, map, expectedMap)) {
            return null;
        }

        final MapValueData valueFrom = map.getForModify(fromKey);
        final MapValueData valueTo = map.getForModify(toKey);

        if (valueFrom == null || valueTo == null) {
            return null;
        }

        valueFrom.setBalance(valueFrom.getBalance() - balance);
        valueTo.setBalance(valueTo.getBalance() + balance);

        map.replace(fromKey, valueFrom);
        map.replace(toKey, valueTo);

        if (valueFrom.isImmutable() || valueTo.isImmutable()) {
            logger.error(DEMO_TRANSACTION_INFO, "VALUES ARE IMMUTABLE");
        }

        return Pair.of(valueFrom, valueTo);
    }

    private static boolean entityMissingFromMap(
            MapKey key, MerkleMap<MapKey, MapValueData> map, ExpectedFCMFamily expectedMap) {
        if (!map.containsKey(key)) {
            if (!expectedMap.entityHasBeenRemoved(key)) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Entity for {} was missing from map but was not deleted by prior transaction",
                        key);
            }
            return true;
        }
        return false;
    }

    private static boolean entityMissingFromMapFCQ(
            MapKey key, MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map, ExpectedFCMFamily expectedMap) {
        if (!map.containsKey(key)) {
            if (!expectedMap.entityHasBeenRemoved(key)) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Entity for {} was missing from map but was not deleted by prior transaction",
                        key);
            }
            return true;
        }
        return false;
    }

    private static void performDeleteAccount(
            final DeleteAccount deleteAccount,
            final MerkleMap<MapKey, MapValueData> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType) {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountMap is null");
            return;
        }
        final MapKey key =
                new MapKey(deleteAccount.getShardID(), deleteAccount.getRealmID(), deleteAccount.getAccountID());
        final MapValue removedValue = map.remove(key);
        final boolean wasRemoved = removedValue != null;
        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing delete transaction (removal={}) for key {} with root hash {}, originId: {}",
                () -> wasRemoved,
                () -> key,
                map::getRootHash,
                () -> originId);

        // If handled successfully,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, null, HANDLED, Delete, timestamp, originId, false);
    }

    private static void performUpdateAccount(
            final UpdateAccount updateAccount,
            final MerkleMap<MapKey, MapValueData> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType) {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountMap is null");
            return;
        }
        final MapKey key =
                new MapKey(updateAccount.getShardID(), updateAccount.getRealmID(), updateAccount.getAccountID());
        final MapValueData value = map.getForModify(key);
        // update contents in place without creating a new MapValueData
        value.setBalance(updateAccount.getBalance());
        value.setSendThresholdValue(updateAccount.getSendThreshold());
        value.setReceiveThresholdValue(updateAccount.getReceiveThreshold());
        value.setReceiverSignatureRequired(updateAccount.getRequireSignature());

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing update transaction for key {} with root hash {}, originId: {}",
                () -> key,
                map::getRootHash,
                () -> originId);
        map.replace(key, value);

        // If handled successfully,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, value, HANDLED, Update, timestamp, originId, false);
    }

    private static void performAssortedAccount(
            final AssortedAccount assortedAccount,
            final MerkleMap<MapKey, MapValueData> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType)
            throws IOException {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountMap is null");
            return;
        }
        final MapKey key =
                new MapKey(assortedAccount.getShardID(), assortedAccount.getRealmID(), assortedAccount.getAccountID());

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing assorted transaction for key {} with root hash {}, originId: {}",
                () -> key,
                map::getRootHash,
                () -> originId);

        switch (assortedAccount.getTxType()) {
            case Create:
                CreateAccount createAccount = CreateAccount.newBuilder()
                        .setShardID(key.getShardId())
                        .setRealmID(key.getRealmId())
                        .setAccountID(key.getAccountId())
                        .build();
                performCreateAccount(createAccount, map, expectedFCMFamily, originId, timestamp, entityType);
                break;
            case Transfer:
                TransferBalance transferBalance = TransferBalance.newBuilder()
                        .setFromShardID(key.getShardId())
                        .setFromRealmID(key.getRealmId())
                        .setFromAccountID(key.getAccountId())
                        .setToShardID(assortedAccount.getShardIdTo())
                        .setToRealmID(assortedAccount.getRealmIdTo())
                        .setToAccountID(assortedAccount.getAccountIdTo())
                        .setTransferAmount(assortedAccount.getAmountBalance())
                        .build();

                performTransferBetweenAccounts(
                        transferBalance, map, expectedFCMFamily, originId, timestamp, entityType);
                break;
            case Update:
                UpdateAccount updateAccount = UpdateAccount.newBuilder()
                        .setShardID(key.getShardId())
                        .setRealmID(key.getRealmId())
                        .setAccountID(key.getAccountId())
                        .setBalance(assortedAccount.getAmountBalance())
                        .setSendThreshold(assortedAccount.getSendThreshold())
                        .setReceiveThreshold(assortedAccount.getReceiveThreshold())
                        .setRequireSignature(assortedAccount.getRequireSignature())
                        .build();
                performUpdateAccount(updateAccount, map, expectedFCMFamily, originId, timestamp, entityType);
                break;
            case Delete:
                DeleteAccount deleteAccount = DeleteAccount.newBuilder()
                        .setShardID(key.getShardId())
                        .setRealmID(key.getRealmId())
                        .setAccountID(key.getAccountId())
                        .build();
                performDeleteAccount(deleteAccount, map, expectedFCMFamily, originId, timestamp, entityType);
                break;
        }
    }

    private static void performAssortedFCQ(
            final AssortedFCQ assortedFCQ,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords)
            throws IOException {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountMap is null");
            return;
        }
        final MapKey key = new MapKey(assortedFCQ.getShardID(), assortedFCQ.getRealmID(), assortedFCQ.getAccountID());

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing assorted transaction for key {} with root hash {}, originId: {}",
                () -> key,
                map::getRootHash,
                () -> originId);

        switch (assortedFCQ.getTxType()) {
            case FCQCreate:
                CreateAccountFCQ createAccountFCQ = CreateAccountFCQ.newBuilder()
                        .setShardID(key.getShardId())
                        .setRealmID(key.getRealmId())
                        .setAccountID(key.getAccountId())
                        .setBalance(assortedFCQ.getAmountBalance())
                        .setIndex(assortedFCQ.getIndex())
                        .setContent(assortedFCQ.getContent())
                        .setInitialRecordNum(assortedFCQ.getInitialRecordNum())
                        .build();
                performCreateAccountFCQ(
                        createAccountFCQ,
                        map,
                        expectedFCMFamily,
                        originId,
                        timestamp,
                        entityType,
                        expirationTime,
                        expirationQueue,
                        accountsWithExpiringRecords);
                break;
            case FCQTransfer:
                TransferBalanceFCQ transferBalanceFCQ = TransferBalanceFCQ.newBuilder()
                        .setFromShardID(key.getShardId())
                        .setFromRealmID(key.getRealmId())
                        .setFromAccountID(key.getAccountId())
                        .setToShardID(assortedFCQ.getToShardID())
                        .setToRealmID(assortedFCQ.getToRealmID())
                        .setToAccountID(assortedFCQ.getToAccountID())
                        .setTransferAmount(assortedFCQ.getAmountBalance())
                        .setNewFromContent(assortedFCQ.getContent())
                        .setNewToContent(assortedFCQ.getNewToContent())
                        .build();

                performTransferFCQ(
                        transferBalanceFCQ,
                        map,
                        expectedFCMFamily,
                        originId,
                        timestamp,
                        entityType,
                        expirationTime,
                        expirationQueue,
                        accountsWithExpiringRecords);
                break;
            case FCQUpdate:
                UpdateAccountFCQ updateAccountFCQ = UpdateAccountFCQ.newBuilder()
                        .setShardID(key.getShardId())
                        .setRealmID(key.getRealmId())
                        .setAccountID(key.getAccountId())
                        .setBalance(assortedFCQ.getAmountBalance())
                        .setIndex(assortedFCQ.getIndex())
                        .setContent(assortedFCQ.getContent())
                        .build();
                performUpdateAccountFCQ(
                        updateAccountFCQ,
                        map,
                        expectedFCMFamily,
                        originId,
                        timestamp,
                        entityType,
                        expirationTime,
                        expirationQueue,
                        accountsWithExpiringRecords);
                break;
            case FCQDelete:
                DeleteFCQ deleteFCQ = DeleteFCQ.newBuilder()
                        .setShardID(key.getShardId())
                        .setRealmID(key.getRealmId())
                        .setAccountID(key.getAccountId())
                        .build();
                performDeleteFCQ(deleteFCQ, map, expectedFCMFamily, originId, timestamp, entityType);
                break;
        }
    }

    private static MapValueData createAccount(final CreateAccount createAccount) {
        return new MapValueData(
                createAccount.getBalance(),
                createAccount.getSendThreshold(),
                createAccount.getReceiveThreshold(),
                createAccount.getRequireSignature(),
                createAccount.getUid());
    }

    private static void performTransferFCQ(
            final TransferBalanceFCQ transferBalanceFCQ,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountFCQMap is null");
            return;
        }
        final MapKey fromKey = new MapKey(
                transferBalanceFCQ.getFromShardID(),
                transferBalanceFCQ.getFromRealmID(),
                transferBalanceFCQ.getFromAccountID());

        final MapKey toKey = new MapKey(
                transferBalanceFCQ.getToShardID(),
                transferBalanceFCQ.getToRealmID(),
                transferBalanceFCQ.getToAccountID());

        final long transferAmount = transferBalanceFCQ.getTransferAmount();

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing transfer FCQ transaction for keys {} and {} with root hash {}, originId: {}",
                () -> fromKey,
                () -> toKey,
                map::getRootHash,
                () -> originId);
        boolean isTransferred = executeAccountFCQTransferred(
                transferAmount,
                transferBalanceFCQ.getNewFromContent().toByteArray(),
                fromKey,
                toKey,
                map,
                expectedFCMFamily,
                expirationTime,
                expirationQueue,
                accountsWithExpiringRecords);

        if (!isTransferred) {
            return;
        }

        MapValueFCQ<TransactionRecord> newFromValue = map.get(fromKey);
        MapValueFCQ<TransactionRecord> newToValue = map.get(toKey);
        // If handled successfully, update expectedValue's Hash,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                fromKey, entityType, null, HANDLED, Transfer, timestamp, originId, false);
        expectedFCMFamily.setLatestHandledStatusForKey(
                toKey, entityType, null, HANDLED, Transfer, timestamp, originId, false);
    }

    private static boolean executeAccountFCQTransferred(
            final long transferAmount,
            final byte[] newContent,
            final MapKey fromKey,
            final MapKey toKey,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {

        if (entityMissingFromMapFCQ(fromKey, map, expectedFCMFamily)) {
            return false;
        }
        if (entityMissingFromMapFCQ(toKey, map, expectedFCMFamily)) {
            return false;
        }

        final MapValueFCQ<TransactionRecord> fromValue = map.getForModify(fromKey);
        final MapValueFCQ<TransactionRecord> toValue = map.getForModify(toKey);

        if (fromValue == null || toValue == null) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "FCQ Transfer Entities were checked for presence in map but one or more came back null");
            return false;
        }

        // Transfer from
        final long newFromBalance = fromValue.getBalanceValue() - transferAmount;
        final int fromRecordCount = fromValue.getRecordsSize();
        TransactionRecord newFromTransaction =
                new TransactionRecord(fromRecordCount, newFromBalance, newContent, expirationTime);
        fromValue.getRecords().add(newFromTransaction);
        fromValue.setBalance(new MerkleLong(newFromBalance));
        map.replace(fromKey, fromValue);
        ExpirationUtils.addRecordToExpirationQueue(
                newFromTransaction, fromKey, expirationQueue, accountsWithExpiringRecords);

        // Transfer to - to mimic Hedera, only create the souce transaction record, not the destination
        final long newToBalance = toValue.getBalanceValue() + transferAmount;
        toValue.setBalance(new MerkleLong(newToBalance));
        map.replace(toKey, toValue);

        return true;
    }

    private static void performUpdateAccountFCQ(
            final UpdateAccountFCQ updateAccountFCQ,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountFCQMap is null");
            return;
        }
        final MapKey key = new MapKey(
                updateAccountFCQ.getShardID(), updateAccountFCQ.getRealmID(), updateAccountFCQ.getAccountID());

        final TransactionRecord transactionRecord = new TransactionRecord(
                updateAccountFCQ.getIndex(),
                updateAccountFCQ.getBalance(),
                updateAccountFCQ.getContent().toByteArray(),
                expirationTime);
        final long balance = updateAccountFCQ.getBalance();

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing update FCQ transaction for key {} with root hash {}, originId: {}",
                () -> key,
                map::getRootHash,
                () -> originId);

        boolean isUpdated = executeAccountFCQUpdated(
                balance, key, transactionRecord, map, expirationQueue, accountsWithExpiringRecords);
        if (!isUpdated) {
            return;
        }
        MapValueFCQ<TransactionRecord> value = map.get(key);
        // If handled successfully, update expectedValue's Hash,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, null, HANDLED, Update, timestamp, originId, false);
    }

    private static boolean executeAccountFCQUpdated(
            final long balance,
            final MapKey key,
            final TransactionRecord transactionRecord,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {

        final MapValueFCQ<TransactionRecord> value = map.getForModify(key);

        if (value == null) {
            logger.error(EXCEPTION.getMarker(), "FCQ: from Key {} for UpdateFCQ doesn't exist", () -> key);
            return false;
        }

        value.getRecords().add(transactionRecord);
        value.setBalance(new MerkleLong(balance));
        map.replace(key, value);
        ExpirationUtils.addRecordToExpirationQueue(
                transactionRecord, key, expirationQueue, accountsWithExpiringRecords);
        return true;
    }

    private static void performDeleteFCQNode(
            final DeleteFCQNode deleteFCQNode,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType) {
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountFCQMap is null");
            return;
        }

        final MapKey key =
                new MapKey(deleteFCQNode.getShardID(), deleteFCQNode.getRealmID(), deleteFCQNode.getAccountID());
        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing delete FCQ Node transaction for key {}, originId: {}",
                () -> key,
                () -> originId);
        final int index = (int) deleteFCQNode.getIndex();

        boolean isDeleted = isFCQRecordDeleted(index, key, map);

        if (!isDeleted) {
            return;
        }
        // If handled successfully, update expectedValue's Hash,
        // and set latestHandledStatus
        MapValueFCQ<TransactionRecord> newValue = map.get(key);
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, null, HANDLED, Update, timestamp, originId, false);
    }

    private static void performDeleteFCQ(
            final DeleteFCQ deleteFCQ,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> accountFCQMap,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType) {

        if (accountFCQMap == null) {
            logger.error(EXCEPTION.getMarker(), "accountFCQMap is null");
            return;
        }

        final MapKey key = new MapKey(deleteFCQ.getShardID(), deleteFCQ.getRealmID(), deleteFCQ.getAccountID());

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing delete FCQ transaction for key {}, originId: " + "{}",
                () -> key,
                () -> originId);

        final MapValue removedValue = accountFCQMap.remove(key);
        final boolean wasRemoved = removedValue != null;
        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing delete FCQ transaction (removal={}) for key {} with root hash {}, originId: {}",
                () -> wasRemoved,
                () -> key,
                accountFCQMap::getRootHash,
                () -> originId);

        // If handled successfully,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, null, HANDLED, Delete, timestamp, originId, false);
    }

    private static boolean isFCQRecordDeleted(
            final int index, final MapKey key, final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map) {

        MapValueFCQ<TransactionRecord> value = map.getForModify(key);

        try {
            if (value == null) {
                logger.error(EXCEPTION.getMarker(), "FCQ: Key {} for delete doesn't exist", () -> key);
                return false;
            }

            final int recordsSize = value.getRecordsSize();
            if (recordsSize == 0) {
                logger.info(
                        DEMO_TRANSACTION_INFO, "FCQ: Key {} has an empty records when deleting index {}", key, index);
                return false;
            }

            if (index >= recordsSize) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "FCQ: Key {} for delete at index {} is out of bounds for list size {}",
                        key,
                        index,
                        recordsSize);
                return false;
            }

            value = value.deleteFirst();
        } finally {
            if (value != null) {
                map.replace(key, value);
            }
        }

        return true;
    }

    private static void performCreateAccountFCQ(
            final CreateAccountFCQ createAccountFCQ,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final ExpectedFCMFamily expectedFCMFamily,
            final long originId,
            final long timestamp,
            final EntityType entityType,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        final MapKey key = new MapKey(
                createAccountFCQ.getShardID(), createAccountFCQ.getRealmID(), createAccountFCQ.getAccountID());
        if (map == null) {
            logger.error(EXCEPTION.getMarker(), "accountFCQMap is null");
            return;
        }

        logger.trace(
                DEMO_TRANSACTION_INFO,
                "Processing create FCQ transaction for key {} with root hash {}, originId: {}",
                () -> key,
                map::getRootHash,
                () -> originId);
        boolean isCreated = executeAccountFCQCreated(
                createAccountFCQ, map, key, expirationTime, expirationQueue, accountsWithExpiringRecords);

        if (!isCreated) {
            return;
        }

        final MapValueFCQ<TransactionRecord> value = map.get(key);
        // If handled successfully, set expectedValue's Hash,
        // and set latestHandledStatus
        expectedFCMFamily.setLatestHandledStatusForKey(
                key, entityType, value, HANDLED, Create, timestamp, originId, false);
    }

    private static boolean executeAccountFCQCreated(
            final CreateAccountFCQ createAccountFCQ,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> map,
            final MapKey key,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        MapValueFCQ<TransactionRecord> value = MapValueFCQ.newBuilder()
                .setIndex(createAccountFCQ.getIndex())
                .setBalance(createAccountFCQ.getBalance())
                .setContent(createAccountFCQ.getContent().toByteArray())
                .setMapKey(key)
                .setExpirationQueue(expirationQueue)
                .setExpirationTime(expirationTime)
                .setAccountsWithExpiringRecords(accountsWithExpiringRecords)
                .build();

        if (key == null || value == null) {
            return false;
        }

        // the number of records we shall insert into FCQ after creating this account
        final long initialRecordNum = createAccountFCQ.getInitialRecordNum();
        byte[] content = new byte[createAccountFCQ.getContent().toByteArray().length];
        List<TransactionRecord> records = new ArrayList<>();
        for (int i = 1; i < initialRecordNum; i++) {
            final TransactionRecord transactionRecord =
                    new TransactionRecord(i, createAccountFCQ.getBalance(), content, expirationTime);
            records.add(transactionRecord);
        }
        value = value.addRecords(
                createAccountFCQ.getBalance(), records, key, expirationQueue, accountsWithExpiringRecords);
        map.put(key, value);

        final int recordSize = value.getRecordsSize();

        if (initialRecordNum != recordSize) {
            logger.info(
                    DEMO_TRANSACTION_INFO,
                    "Error when processing create FCQ transaction for MapValueFCQ, key {}."
                            + " expectedRecordNum {}, actualRecordNum {}",
                    () -> key,
                    () -> initialRecordNum,
                    () -> recordSize);
        }
        return true;
    }
}
