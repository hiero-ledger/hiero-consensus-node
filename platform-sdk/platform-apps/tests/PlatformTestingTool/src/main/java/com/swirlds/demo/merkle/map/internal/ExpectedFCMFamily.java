// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map.internal;

import com.swirlds.demo.merkle.map.FCMConfig;
import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.platform.PayloadCfgSimple;
import com.swirlds.demo.platform.PayloadConfig;
import com.swirlds.demo.platform.nft.NftId;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.MapValue;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ExpectedFCMFamily extends Serializable {

    void addEntityToExpectedMap(final MapKey mapKey, final ExpectedValue expectedValue);

    LifecycleStatus buildLifecycleStatusFromPayload(byte[] payload, PayloadConfig payloadConfig);

    boolean entityHasBeenRemoved(final MapKey mapKey);

    Map<MapKey, ExpectedValue> getExpectedMap();

    MapKey getMapKeyForFCMTx(
            final TransactionType txType,
            final EntityType entityType,
            final boolean performOnDeleted,
            final boolean operateEntitiesOfSameNode,
            final int restrictedCount);

    Optional<NftId> getAnyNftId();

    /**
     * Check if a token with a given ID exists in the ledger.
     */
    boolean doesTokenWithIdExist(final NftId id);

    void addNftId(final NftId nftId);

    boolean removeNftid(final NftId nftId);

    long getNextIdToCreate();

    boolean insertMissingEntity(
            byte[] payload, ExpectedFCMFamily expectedFCMFamily, MapKey key, PayloadConfig payloadConfig);

    void rebuildExpectedMap(final FCMFamily fcmFamily, final boolean isRestart, final long timestamp);

    void setLatestHandledStatusForKey(
            final MapKey mapKey,
            final EntityType entityType,
            final MapValue mapValue,
            final TransactionState state,
            final TransactionType transactionType,
            final long timestamp,
            final long nodeId,
            final boolean error);

    boolean shouldHandleForKeys(
            final List<MapKey> mapKeys,
            final TransactionType transactionType,
            final PayloadCfgSimple payloadCfgSimple,
            final EntityType entityType,
            final long epochMillis,
            final long originId);

    void waitWhileExpectedMapEmpty(final EntityType entityType, final PAYLOAD_TYPE type);

    /**
     * return a MapKey to be queried on current state:
     * when the entityList is not empty, return a random key of the given type;
     * when the entityList is empty, return 0.0.0
     *
     * @param entityType
     * 		type of the entity
     * @return a MapKey to be queried on current state
     * @throws IllegalArgumentException
     * 		if the entityType is unknown
     */
    MapKey getMapKeyForQuery(final EntityType entityType);

    /**
     * Set the ID for this node.
     */
    void setNodeId(final long nodeId);

    /**
     * Set the test configuration.
     */
    void setFcmConfig(final FCMConfig fcmConfig);

    /**
     * Set the number of non-zero-weight nodes in the address book.
     */
    void setWeightedNodeNum(final int weightedNodeNum);
}
