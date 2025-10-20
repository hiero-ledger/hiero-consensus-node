// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_CLASS_ID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.utility.ThresholdLimitingHandler;
import com.swirlds.demo.merkle.map.FCMConfig;
import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamilyImpl;
import com.swirlds.demo.platform.actions.QuorumResult;
import com.swirlds.demo.platform.expiration.ExpirationRecordEntry;
import com.swirlds.demo.platform.expiration.ExpirationUtils;
import com.swirlds.demo.platform.iss.IssLeaf;
import com.swirlds.demo.platform.nft.NftId;
import com.swirlds.demo.platform.nft.NftLedger;
import com.swirlds.demo.platform.nft.ReferenceNftLedger;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This demo tests platform features and collects statistics on the running of the network and consensus systems. It
 * writes them to the screen, and also saves them to disk in a comma separated value (.csv) file. Each transaction
 * consists of an optional sequence number and random bytes.
 */
@ConstructableIgnored
public class PlatformTestingToolState extends MerkleStateRoot<PlatformTestingToolState> implements MerkleNodeState {

    static final long CLASS_ID = 0xc0900cfa7a24db76L;
    private static final Logger logger = LogManager.getLogger(PlatformTestingToolState.class);
    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");
    private static final long EXCEPTION_RATE_THRESHOLD = 10;

    /**
     * The fraction (out of 1.0) of NFT tokens to track in the reference data structure.
     */
    private static final double NFT_TRACKING_FRACTION = 0.001;
    /**
     * Has init() been called on this copy or an ancestor copy of this object?
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Used for validation of part (or all) of the data in the NFT ledger.
     */
    private final ReferenceNftLedger referenceNftLedger;
    ///////////////////////////////////////////
    // Copyable variables
    private long lastTranTimeStamp = 0;
    private ThresholdLimitingHandler<Throwable> exceptionRateLimiter;
    private ProgressCfg progressCfg;
    ///////////////////////////////////////////
    // Variables not used for state copyTo
    protected long roundCounter = 0;
    private long lastFileTranFinishTimeStamp = 0;
    ///////////////////////////////////////////
    // Variables used for check and validate result
    @JsonIgnore
    private ExpectedFCMFamily expectedFCMFamily;
    // is used for evicting expired records in the beginning of .handleTransaction
    private BlockingQueue<ExpirationRecordEntry> expirationQueue;
    // contains MapKeys which has an entry in ExpirationQueue
    private Set<MapKey> accountsWithExpiringRecords;

    /**
     * NodeId of the node that this state belongs to.
     */
    private NodeId selfId;

    public PlatformTestingToolState() {
        expectedFCMFamily = new ExpectedFCMFamilyImpl();
        referenceNftLedger = new ReferenceNftLedger(NFT_TRACKING_FRACTION);
    }

    protected PlatformTestingToolState(final PlatformTestingToolState sourceState) {
        super(sourceState);
        this.initialized.set(sourceState.initialized.get());
        this.lastFileTranFinishTimeStamp = sourceState.lastFileTranFinishTimeStamp;
        this.lastTranTimeStamp = sourceState.lastTranTimeStamp;

        // Only a small fraction of tokens are tracked in this reference leger, so this deep copy should be fast.
        this.referenceNftLedger = sourceState.referenceNftLedger.copy();

        // not part of copy to and from stream
        this.expectedFCMFamily = sourceState.expectedFCMFamily;
        this.expirationQueue = sourceState.expirationQueue;
        this.accountsWithExpiringRecords = sourceState.accountsWithExpiringRecords;
        this.exceptionRateLimiter = sourceState.exceptionRateLimiter;
        // end enhanced control structures

        this.progressCfg = sourceState.progressCfg;
        this.roundCounter = sourceState.roundCounter;

        this.selfId = sourceState.selfId;

        if (sourceState.getTransactionCounter() != null) {
            final int size = sourceState.getTransactionCounter().size();
            setTransactionCounter(new TransactionCounterList(size));
            for (int index = 0; index < size; index++) {
                getTransactionCounter()
                        .add(
                                index,
                                sourceState.getTransactionCounter().get(index).copy());
            }
        }
        setImmutable(false);
        sourceState.setImmutable(true);
    }

    // count invalid signature ratio
    static AtomicLong totalTransactionSignatureCount = new AtomicLong(0);
    static AtomicLong expectedInvalidSignatureCount = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumChildCount() {
        return ChildIndices.SDK_VERSION_21_CHILD_COUNT;
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
                // We used to use this for an address book, but now we don't use this index.
                // Ignore whatever is found at this index.
                // platform should be here, so check for singleton if all will be ok
                return true;
            case ChildIndices.CONFIG:
                return childClassId == PayloadCfgSimple.CLASS_ID;
            case ChildIndices.NEXT_SEQUENCE_CONSENSUS:
                return childClassId == NextSeqConsList.CLASS_ID;
            case ChildIndices.FCM_FAMILY:
                return childClassId == FCMFamily.CLASS_ID || childClassId == NULL_CLASS_ID;
            case ChildIndices.TRANSACTION_COUNTER:
                return childClassId == TransactionCounterList.CLASS_ID;
            case ChildIndices.ISS_LEAF:
                return childClassId == IssLeaf.CLASS_ID;
            case ChildIndices.NFT_LEDGER:
                return childClassId == NftLedger.CLASS_ID;
            case ChildIndices.VIRTUAL_MERKLE:
                return childClassId == VirtualMap.CLASS_ID || childClassId == NULL_CLASS_ID;
            case ChildIndices.VIRTUAL_MERKLE_SMART_CONTRACTS:
                return childClassId == VirtualMap.CLASS_ID || childClassId == NULL_CLASS_ID;
            case ChildIndices.VIRTUAL_MERKLE_SMART_CONTRACTS_BYTE_CODE:
                return childClassId == VirtualMap.CLASS_ID || childClassId == NULL_CLASS_ID;
            case ChildIndices.QUORUM_RESULT:
                return childClassId == QuorumResult.CLASS_ID;
            default:
                return false;
        }
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

    // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19002
    @Override
    public MerkleNode migrate(int version) {
        return this;
    }

    PayloadCfgSimple getConfig() {
        return getChild(ChildIndices.CONFIG);
    }

    void setConfig(final PayloadCfgSimple config) {
        setChild(ChildIndices.CONFIG, config);
    }

    public NextSeqConsList getNextSeqCons() {
        return getChild(ChildIndices.NEXT_SEQUENCE_CONSENSUS);
    }

    void setNextSeqCons(final NextSeqConsList nextSeqCons) {
        setChild(ChildIndices.NEXT_SEQUENCE_CONSENSUS, nextSeqCons);
    }

    public VirtualMap getVirtualMap() {
        return getChild(ChildIndices.VIRTUAL_MERKLE);
    }

    public void setVirtualMap(final VirtualMap virtualMap) {
        setChild(ChildIndices.VIRTUAL_MERKLE, virtualMap);
    }

    public VirtualMap getVirtualMapForSmartContracts() {
        return getChild(ChildIndices.VIRTUAL_MERKLE_SMART_CONTRACTS);
    }

    public void setVirtualMapForSmartContracts(final VirtualMap virtualMap) {
        setChild(ChildIndices.VIRTUAL_MERKLE_SMART_CONTRACTS, virtualMap);
    }

    public VirtualMap getVirtualMapForSmartContractsByteCode() {
        return getChild(ChildIndices.VIRTUAL_MERKLE_SMART_CONTRACTS_BYTE_CODE);
    }

    public void setVirtualMapForSmartContractsByteCode(final VirtualMap virtualMap) {
        setChild(ChildIndices.VIRTUAL_MERKLE_SMART_CONTRACTS_BYTE_CODE, virtualMap);
    }

    public FCMFamily getFcmFamily() {
        return getChild(ChildIndices.FCM_FAMILY);
    }

    public QuorumResult<ControlAction> getQuorumResult() {
        return getChild(ChildIndices.QUORUM_RESULT);
    }

    public void setFcmFamily(final FCMFamily fcmFamily) {
        setChild(ChildIndices.FCM_FAMILY, fcmFamily);
    }

    public TransactionCounterList getTransactionCounter() {
        return getChild(ChildIndices.TRANSACTION_COUNTER);
    }

    void setTransactionCounter(final TransactionCounterList transactionCounter) {
        setChild(ChildIndices.TRANSACTION_COUNTER, transactionCounter);
    }

    IssLeaf getIssLeaf() {
        return getChild(ChildIndices.ISS_LEAF);
    }

    void setIssLeaf(final IssLeaf leaf) {
        setChild(ChildIndices.ISS_LEAF, leaf);
    }

    public void setQuorumResult(final QuorumResult<ControlAction> quorumResult) {
        setChild(ChildIndices.QUORUM_RESULT, quorumResult);
    }

    /**
     * Get the NFT ledger, a child of this node.
     */
    public NftLedger getNftLedger() {
        return getChild(ChildIndices.NFT_LEDGER);
    }

    /**
     * Set the NFT ledger, a child of this node.
     */
    public void setNftLedger(final NftLedger ledger) {
        setChild(ChildIndices.NFT_LEDGER, ledger);
    }

    /**
     * Get the reference NFT ledger.
     */
    public ReferenceNftLedger getReferenceNftLedger() {
        return referenceNftLedger;
    }

    public BlockingQueue<ExpirationRecordEntry> getExpirationQueue() {
        return expirationQueue;
    }

    // contains MapKeys which has an entry in ExpirationQueue
    public Set<MapKey> getAccountsWithExpiringRecords() {
        return accountsWithExpiringRecords;
    }

    public synchronized void setPayloadConfig(final FCMConfig fcmConfig, final Roster roster) {
        expectedFCMFamily.setNodeId(selfId.id());
        expectedFCMFamily.setFcmConfig(fcmConfig);
        expectedFCMFamily.setWeightedNodeNum(RosterUtils.getNumberWithWeight(roster));

        referenceNftLedger.setFractionToTrack(this.getNftLedger(), fcmConfig.getNftTrackingFraction());
    }

    public void setProgressCfg(final ProgressCfg progressCfg) {
        this.progressCfg = progressCfg;
    }

    void setSelfId(NodeId selfId) {
        this.selfId = selfId;
    }

    public void initChildren() {
        if (getFcmFamily() == null) { // if not loaded from previous saved state
            setFcmFamily(new FCMFamily(true));
        }

        if (getNftLedger() == null) {
            setNftLedger(new NftLedger());
        }
    }

    public void initializeExpirationQueueAndAccountsSet() {
        expirationQueue = new PriorityBlockingQueue<>();
        accountsWithExpiringRecords = new HashSet<>();
    }

    public void resetLastFileTranFinishTimeStamp() {
        lastFileTranFinishTimeStamp = System.currentTimeMillis();
    }

    public synchronized FCMFamily getStateMap() {
        return getFcmFamily();
    }

    @JsonIgnore
    public synchronized ExpectedFCMFamily getStateExpectedMap() {
        return expectedFCMFamily;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized PlatformTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        roundCounter++;

        final PlatformTestingToolState mutableCopy = new PlatformTestingToolState(this);

        if (selfId != null) {
            UnsafeMutablePTTStateAccessor.getInstance().setMutableState(selfId, mutableCopy);
        }

        return mutableCopy;
    }

    /**
     * after reconnect/restart, rebuild ExpectedMap from state
     *
     * @param consensusTimestamp consensusTimestamp of the state which is received during reconnect or loaded after
     *                           restart
     * @param isRestart          Whether this is part of a workflow restart
     */
    public void rebuildExpectedMapFromState(final Instant consensusTimestamp, final boolean isRestart) {
        // rebuild ExpectedMap
        logger.info(LOGM_DEMO_INFO, "Start Rebuilding ExpectedMap");
        this.expectedFCMFamily.rebuildExpectedMap(getStateMap(), isRestart, 0);
        logger.info(LOGM_DEMO_INFO, "Finish Rebuilding ExpectedMap [ size = {} ]", () -> expectedFCMFamily
                .getExpectedMap()
                .size());
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
        return ClassVersion.ADD_QUORUM_RESULT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        // We never do migration with this state, so always fail if we are given an older version.
        return getVersion();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void rebuild() {
        final boolean isMMapInitialized = this.getStateMap() != null;
        logger.info(LOGM_DEMO_INFO, "Finished initializing PlatformTestingDemoState with map {}", isMMapInitialized);
        if (isMMapInitialized) {
            logger.info(
                    LOGM_DEMO_INFO,
                    "The fcq MerkleMap has size {}",
                    this.getStateMap().getAccountFCQMap().size());
            logger.info(
                    LOGM_DEMO_INFO,
                    "The account MerkleMap has size {}",
                    this.getStateMap().getMap().size());
        }

        for (final NftId nftId : getNftLedger().getTokenIdToToken().keySet()) {
            expectedFCMFamily.addNftId(nftId);
        }

        getReferenceNftLedger().reload(getNftLedger());
    }

    long removeExpiredRecordsInExpirationQueue(final long consensusCurrentTimestamp) {
        long removedNumOfRecords = 0;
        while (!expirationQueue.isEmpty()
                && (expirationQueue.peek().getEarliestExpiry() <= consensusCurrentTimestamp)) {
            final ExpirationRecordEntry ere = expirationQueue.poll();
            final MapKey id = ere.getMapKey().copy();
            final FCMFamily fcmFamily = getFcmFamily();
            removedNumOfRecords += ExpirationUtils.purgeTransactionRecords(
                    consensusCurrentTimestamp,
                    expirationQueue,
                    accountsWithExpiringRecords,
                    id,
                    fcmFamily,
                    expectedFCMFamily);
        }
        return removedNumOfRecords;
    }

    /**
     * Rebuild Expiration Queue of transaction records with earliest expiry time after restart or reconnect
     */
    public void rebuildExpirationQueue() {
        final FCMFamily fcmFamily = getFcmFamily();
        if (fcmFamily == null || fcmFamily.getAccountFCQMap() == null) {
            logger.error(EXCEPTION.getMarker(), "FCMFamily is null, so could not rebuild Expiration Queue");
            return;
        }
        if (fcmFamily.getAccountFCQMap().isEmpty()) {
            return;
        }
        expirationQueue = new PriorityBlockingQueue<>();
        accountsWithExpiringRecords = new HashSet<>();
        ExpirationUtils.addRecordsDuringRebuild(fcmFamily, expirationQueue, accountsWithExpiringRecords);
    }

    /**
     * Useful for unit tests.
     */
    public void setExpectedFCMFamily(final ExpectedFCMFamily expectedFCMFamily) {
        this.expectedFCMFamily = expectedFCMFamily;
    }

    @Override
    protected PlatformTestingToolState copyingConstructor() {
        return new PlatformTestingToolState(this);
    }

    @Override
    public long singletonPath(final int stateId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long queueElementPath(final int stateId, @NonNull final Bytes expectedValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long kvPath(final int stateId, @NonNull final Bytes key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Hash getHashForPath(long path) {
        throw new UnsupportedOperationException();
    }

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
         * In this version, removed FCMOperations class
         */
        public static final int REMOVED_FCM_OPERATIONS = 3;
        /**
         * In this version the NFT ledger was added to the state.
         */
        public static final int ADDED_NFT_LEDGER = 4;

        /**
         * In this version the quorum result was added to the state.
         */
        public static final int ADD_QUORUM_RESULT = 5;
    }

    private static class ChildIndices {
        public static final int UNUSED_PLATFORM_STATE = 0;
        public static final int UNUSED_ROSTERS = 1;
        public static final int UNUSED_ROSTER_STATE = 2;

        public static final int CONFIG = 3;
        /**
         * last sequence by each member for consensus events
         */
        public static final int NEXT_SEQUENCE_CONSENSUS = 4;

        public static final int FCM_FAMILY = 5;
        public static final int TRANSACTION_COUNTER = 6;
        public static final int ISS_LEAF = 7;
        /**
         * Migration test need this value to be able to load state file generated by v21 sdk
         */
        public static final int SDK_VERSION_21_CHILD_COUNT = 7;

        /**
         * The NFT ledger is very large, so adding it as an nary child is memory inefficient (due to merkle route
         * compression). But this is what Hedera is currently doing, so it is better to mimic their pattern and have
         * similar inefficiencies.
         */
        public static final int NFT_LEDGER = 8;

        public static final int VIRTUAL_MERKLE = 9;

        public static final int VIRTUAL_MERKLE_SMART_CONTRACTS = 10;

        public static final int VIRTUAL_MERKLE_SMART_CONTRACTS_BYTE_CODE = 11;

        public static final int QUORUM_RESULT = 12;

        public static final int CHILD_COUNT = 13;
    }
}
