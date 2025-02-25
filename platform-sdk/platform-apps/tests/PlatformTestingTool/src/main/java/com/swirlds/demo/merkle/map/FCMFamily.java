// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import java.util.Map;

public class FCMFamily extends PartialNaryMerkleInternal implements MerkleInternal {

    /**
     * The version history of this class. Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        /**
         * In this version, serialization was performed by copyTo/copyToExtra and deserialization was performed by
         * copyFrom/copyFromExtra. This version is not supported by later deserialization methods and must be handled
         * specially by the platform.
         */
        public static final int ORIGINAL = 1;
        /**
         * In this version, serialization was performed by serialize/deserialize.
         */
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    public static final long CLASS_ID = 0x4276e41f232aec15L;

    private static class ChildIndices {
        public static final int MAP = 0;
        public static final int ACCOUNT_FCQ_MAP = 1;

        public static final int CHILD_COUNT = 2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren() {
        return ChildIndices.CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumChildCount() {
        return ChildIndices.CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaximumChildCount() {
        return ChildIndices.CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean childHasExpectedType(int index, long childClassId) {
        switch (index) {
            case ChildIndices.MAP:
            case ChildIndices.ACCOUNT_FCQ_MAP:
                return childClassId == MerkleMap.CLASS_ID;
            default:
                return false;
        }
    }

    public MerkleMap<MapKey, MapValueData> getMap() {
        return getChild(ChildIndices.MAP);
    }

    public void setMap(final MerkleMap<MapKey, MapValueData> map) {
        setChild(ChildIndices.MAP, map);
    }

    public MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> getAccountFCQMap() {
        return getChild(ChildIndices.ACCOUNT_FCQ_MAP);
    }

    public void setAccountFCQMap(final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> accountFCQMap) {
        setChild(ChildIndices.ACCOUNT_FCQ_MAP, accountFCQMap);
    }

    public FCMFamily() {}

    /**
     * Initialize the FCMFamily and its children.
     *
     * @param initChildren if true then initialize this node's children
     */
    public FCMFamily(final boolean initChildren) {
        if (initChildren) {
            setMap(new MerkleMap<>());
            setAccountFCQMap(new MerkleMap<>());
        }
    }

    private FCMFamily(final FCMFamily other) {
        super(other);
        setMap(other.getMap().copy());
        setAccountFCQMap(other.getAccountFCQMap().copy());
        setImmutable(false);
        other.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FCMFamily copy() {
        throwIfImmutable();
        throwIfDestroyed();

        return new FCMFamily(this);
    }

    /**
     * return actual MerkleMap of the entityType
     *
     * @return MerkleMap of the entityType
     */
    public Map<MapKey, ? extends MerkleNode> getActualMap(final EntityType entityType) {
        switch (entityType) {
            case Crypto:
                return this.getMap();
            case FCQ:
                return this.getAccountFCQMap();
            default:
                throw new IllegalArgumentException("Unknown EntityType: " + entityType);
        }
    }

    /**
     * get total number of entities in this FCMFamily
     *
     * @return total entities across all MerkleMaps
     */
    public long getTotalCount() {
        return getMap().size() + this.getAccountFCQMap().size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }
}
