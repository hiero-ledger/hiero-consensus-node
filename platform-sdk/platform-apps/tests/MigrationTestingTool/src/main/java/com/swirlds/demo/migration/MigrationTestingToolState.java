// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.model.roster.AddressBook;

@ConstructableIgnored
public class MigrationTestingToolState extends MerkleStateRoot<MigrationTestingToolState> implements MerkleNodeState {

    private static final Logger logger = LogManager.getLogger(MigrationTestingToolState.class);

    private static final long INITIAL_ACCOUNTS_HINT = 1_000_000;

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
        /**
         * Migrate from the old FCMap to the new MerkleMap
         */
        public static final int MERKLE_MAP_REFACTOR = 3;
        /**
         * Add a virtual map and remove all blobs.
         */
        public static final int VIRTUAL_MAP = 4;
        /**
         * Added ROSTERS and ROSTER_STATE
         */
        public static final int ROSTERS = 5;
    }

    private static final long CLASS_ID = 0x1a0daec64a09f6a4L;

    /**
     * A record of the positions of each child within this node.
     */
    private static class ChildIndices {
        public static final int UNUSED_PLATFORM_STATE = 0;
        public static final int UNUSED_ROSTERS = 1;
        public static final int UNUSED_ROSTER_STATE = 2;
        public static final int MERKLE_MAP = 3;
        public static final int VIRTUAL_MAP = 4;

        public static final int CHILD_COUNT = 5;

        // these constants are to migrate from v4 to v5
        public static final int OLD_CHILD_COUNT = 3;
    }

    public MigrationTestingToolState() {}

    private MigrationTestingToolState(final MigrationTestingToolState that) {
        super(that);
        that.setImmutable(true);
        this.setImmutable(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumChildCount() {
        return ChildIndices.OLD_CHILD_COUNT;
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
    public boolean childHasExpectedType(final int index, final long childClassId) {
        switch (index) {
            case ChildIndices.UNUSED_PLATFORM_STATE:
            case ChildIndices.UNUSED_ROSTERS:
            case ChildIndices.UNUSED_ROSTER_STATE:
                // Reserved for system states.
                return true;
            case ChildIndices.MERKLE_MAP:
                return childClassId == MerkleMap.CLASS_ID;
            case ChildIndices.VIRTUAL_MAP:
                return childClassId == VirtualMap.CLASS_ID;
            default:
                return false;
        }
    }

    @Override
    public MerkleNode migrate(@NonNull final Configuration configuration, int version) {
        if (version == ClassVersion.VIRTUAL_MAP) {
            TestingAppStateInitializer.DEFAULT.initRosterState(this);
            return this;
        }

        // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19002
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDeserializedChildren(final List<MerkleNode> children, final int version) {
        if (!children.isEmpty() && children.get(0) instanceof AddressBook) {
            // We used to store an address book here, but we can ignore it now.
            children.set(0, null);
        }

        super.addDeserializedChildren(children, version);
    }

    /**
     * Get a {@link MerkleMap} that contains various data.
     */
    MerkleMap<AccountID, MapValue> getMerkleMap() {
        return getChild(ChildIndices.MERKLE_MAP);
    }

    /**
     * Set a {@link MerkleMap} that contains various data.
     */
    void setMerkleMap(final MerkleMap<AccountID, MapValue> map) {
        throwIfImmutable();
        setChild(ChildIndices.MERKLE_MAP, map);
    }

    /**
     * Get a {@link VirtualMap} that contains various data.
     */
    VirtualMap getVirtualMap() {
        return getChild(ChildIndices.VIRTUAL_MAP);
    }

    /**
     * Set a {@link VirtualMap} that contains various data.
     */
    protected void setVirtualMap(final VirtualMap map) {
        setChild(ChildIndices.VIRTUAL_MAP, map);
    }

    /**
     * Do genesis initialization.
     */
    void genesisInit() {
        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        setMerkleMap(new MerkleMap<>());
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final MerkleDbTableConfig tableConfig = new MerkleDbTableConfig(
                (short) 1, DigestType.SHA_384, INITIAL_ACCOUNTS_HINT, merkleDbConfig.hashesRamToDiskThreshold());
        // to make it work for the multiple node in one JVM case, we need reset the default instance path every time
        // we create another instance of MerkleDB.
        MerkleDb.resetDefaultInstancePath();
        final VirtualDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, configuration);
        setVirtualMap(new VirtualMap("virtualMap", dsBuilder, configuration));
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MigrationTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new MigrationTestingToolState(this);
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
        return ClassVersion.ROSTERS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.VIRTUAL_MAP;
    }

    @Override
    protected MigrationTestingToolState copyingConstructor() {
        return new MigrationTestingToolState(this);
    }
}
