// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Append;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.BurnToken;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.MintToken;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Transfer;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.TransferToken;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;

import com.swirlds.demo.platform.fs.stresstest.proto.BurnToken;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTxType;
import com.swirlds.demo.platform.fs.stresstest.proto.FCQTxType;
import com.swirlds.demo.platform.fs.stresstest.proto.MintToken;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalance;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalanceFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferToken;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class FCMTransactionUtils {
    private static final Logger logger = LogManager.getLogger(FCMTransactionHandler.class);
    private static final Marker DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");

    /**
     * get TransactionType of a FCMTransaction
     *
     * @param fcmTransaction
     * @return
     */
    public static TransactionType getTransactionType(final FCMTransaction fcmTransaction) {
        String bodyCase = fcmTransaction.getBodyCase().toString();
        if (bodyCase.contains("CREATE")) {
            return Create;
        } else if (bodyCase.contains("UPDATE")) {
            return Update;
        } else if (bodyCase.contains("TRANSFER")) {
            return Transfer;
        } else if (bodyCase.contains("DELETE_FCQ_NODE")) {
            return Update;
        } else if (bodyCase.contains("DELETE")) {
            return Delete;
        } else if (bodyCase.contains("APPEND")) {
            return Append;
        } else if (bodyCase.contains("MINTTOKEN")) {
            return MintToken;
        } else if (bodyCase.contains("TRANSFERTOKEN")) {
            return TransferToken;
        } else if (bodyCase.contains("BURNTOKEN")) {
            return BurnToken;
        } else if (bodyCase.contains("ASSORTED")) {
            if (bodyCase.contains("FCQ")) {
                FCQTxType txType = fcmTransaction.getAssortedFCQ().getTxType();
                switch (txType) {
                    case FCQCreate:
                        return Create;
                    case FCQTransfer:
                        return Transfer;
                    case FCQUpdate:
                        return Update;
                    case FCQDelete:
                        return Delete;
                    default:
                        logger.error(
                                EXCEPTION.getMarker(), "Invalid Assorted FCQ body in FCMTransaction: {}", bodyCase);
                        return null;
                }

            } else {
                FCMTxType txType = fcmTransaction.getAssortedAccount().getTxType();
                switch (txType) {
                    case Create:
                        return Create;
                    case Transfer:
                        return Transfer;
                    case Update:
                        return Update;
                    case Delete:
                        return Delete;
                    default:
                        logger.error(EXCEPTION.getMarker(), "Invalid Assorted body in FCMTransaction: {}", bodyCase);
                        return null;
                }
            }
        } else {
            logger.error(EXCEPTION.getMarker(), "Invalid body for TransactionType in FCMTransaction: {}", bodyCase);
            return null;
        }
    }

    /**
     * get EntityType of a FCMTransaction
     *
     * @param fcmTransaction
     * @return
     */
    public static EntityType getEntityType(final FCMTransaction fcmTransaction) {
        String bodyCase = fcmTransaction.getBodyCase().toString();

        if (bodyCase.contains("FCQ")) {
            return EntityType.FCQ;
        } else if (bodyCase.contains("ACCOUNT") || bodyCase.equals("TRANSFERBALANCE")) {
            return EntityType.Crypto;
        } else if (bodyCase.contains("TOKEN")) {
            return EntityType.NFT;
        } else {
            logger.error(EXCEPTION.getMarker(), "Invalid body for EntityType in FCMTransaction: {}", bodyCase);
            return null;
        }
    }

    /**
     * get mapKey of entity in a FCMTransaction;
     * for Transfer transactions, return a list of two mapKeys (transferFrom account and transferTo account)
     * for other transactions, return a list of one mapKey
     *
     * @param fcmTransaction
     * @return
     */
    public static List<MapKey> getMapKeys(final FCMTransaction fcmTransaction) {
        Object body = getBodyField(fcmTransaction);
        List<MapKey> mapKeys = new ArrayList<>();
        if (body instanceof TransferBalance || body instanceof TransferBalanceFCQ) {
            addMapKey(mapKeys, body, "getFromShardID", "getFromRealmID", "getFromAccountID");
            addMapKey(mapKeys, body, "getToShardID", "getToRealmID", "getToAccountID");
        } else if (!isBodyRelatedToNft(body)) {
            addMapKey(mapKeys, body, "getShardID", "getRealmID", "getAccountID");
        }

        return mapKeys;
    }

    private static boolean isBodyRelatedToNft(final Object body) {
        return body instanceof TransferToken || body instanceof BurnToken || body instanceof MintToken;
    }

    /**
     * Build a MapKey from a FCMTransaction body message, and add to the list
     *
     * @param mapKeys
     * @param body
     * @param getShardIDStr
     * 		method name for getting shardID
     * @param getRealmIDStr
     * 		method name for getting realmID
     * @param getAccountIDStr
     * 		method name for getting accountID
     * @return
     */
    private static void addMapKey(
            final List<MapKey> mapKeys,
            final Object body,
            final String getShardIDStr,
            final String getRealmIDStr,
            final String getAccountIDStr) {
        try {
            Method getShardID, getRealmID, getAccountID;
            getShardID = body.getClass().getMethod(getShardIDStr);
            getRealmID = body.getClass().getMethod(getRealmIDStr);
            getAccountID = body.getClass().getMethod(getAccountIDStr);

            long shardId, realmId, accountId;
            shardId = (long) getShardID.invoke(body);
            realmId = (long) getRealmID.invoke(body);
            accountId = (long) getAccountID.invoke(body);

            mapKeys.add(new MapKey(shardId, realmId, accountId));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            logger.error(EXCEPTION.getMarker(), "Exception when extracting MapKey " + "from FCMTransaction. {}", e);
        }
    }

    /**
     * A FCMTransaction message has a oneof type field body,
     * it can be CreateAccount, TransferBalance, etc.
     * This method return the specific body object contained in this FCMTransaction Message
     *
     * @param fcmTransaction
     * @return
     */
    public static Object getBodyField(final FCMTransaction fcmTransaction) {
        return fcmTransaction.getField(fcmTransaction
                .getDescriptorForType()
                .findFieldByNumber(fcmTransaction.getBodyCase().getNumber()));
    }

    /**
     * get origin nodeId from the FCMTransaction
     *
     * @param fcmTransaction
     * @return
     */
    public static long getNodeId(final FCMTransaction fcmTransaction) {
        return fcmTransaction.getOriginNode();
    }
}
