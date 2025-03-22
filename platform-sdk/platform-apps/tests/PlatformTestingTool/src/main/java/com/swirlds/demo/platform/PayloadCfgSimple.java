// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord.DEFAULT_EXPIRATION_TIME;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class PayloadCfgSimple extends PartialMerkleLeaf implements MerkleLeaf {

    private static final Logger logger = LogManager.getLogger(PayloadCfgSimple.class);
    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");

    /**
     * The version history of this class.
     * Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        private static final int ORIGINAL = 1;
        /**
         * In this version, serialization was performed by serialize/deserialize.
         */
        private static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    public static final long CLASS_ID = 0x1c08a95ca87e3e0eL;

    /** whether insert sequence number at beginning of random payload */
    private boolean insertSeq = false;
    /** whether append signature after payload */
    private boolean appendSig = false;
    /**
     * the ratio of FCMTransactions with invalid signature to all FCMTransactions to be generated;
     * its value should be in range [0, 1]; by default its value is 0, which means all generated
     * FCMTransaction should have valid signature, and have `invalidSig` field be false;
     */
    private double invalidSigRatio = 0;
    /**
     * when it is false, payload bytes size would be payloadByteSize;
     * when it is true, payload bytes size be a random number in range [payloadByteSize, maxByteSize]
     */
    private boolean variedSize = false;

    /**
     * whether enable check after test run. When enableCheck is false TYPE_TEST_PAUSE and TYPE_TEST_SYNC transactions
     * are not needed in sequential test. As there is no TYPE_TEST_PAUSE before delete transactions, if one node is
     * slower than other nodes, other nodes might handle slower nodes transactions after entities are deleted.
     * This might cause performOnDeleted errors. So performOnDeleted is set to true when enableCheck is false
     */
    private boolean enableCheck = true;

    /**
     * Check ExpectedMap of ExpectedFCMFamily with all 3 maps of FCMFamily.
     * This is enabled only if enableCheck is true
     */
    private boolean checkFCM = true;

    /**
     * enable check expiration functionality at end of the test.
     * Checks if any records in FCQueue have expirationTime below lastPurgeTimestamp
     */
    private boolean checkExpiration = true;

    /**
     * define how many seconds we allow records to live in FCQ. By default it is 180 seconds
     */
    private int fcqTtl = DEFAULT_EXPIRATION_TIME;

    /** whether log progress for transaction handling */
    private int progressMarker = 0;

    /** threshold to stop check if amount of error reach this threshold */
    private long errorThreshold = 100;

    /** continue checking even amount of error greater than errorThreshold */
    private boolean continueAfterError = false;

    /** after test finish keep MerkleMap and FCFS log so it will be loaded during restart test */
    boolean keepCheckLog = false;

    /** save internal file logs and expected map to file at the last checking stage */
    boolean saveAppLogState = false;

    /** wait to send ApplicationFinishedPayload until state is saved during freeze */
    boolean waitForSaveStateDuringFreeze = false;

    /**
     * save internal file logs and expected map to file while freezing;
     * for restart test, we should set `saveExpectedMapAtFreeze` to be true, so that
     * ExpectedFCMFamily could be recovered at restart.
     * Note: It might not work with TPS higher than 5k, because the nodes might not be able to finish writing file to
     * disk before being shut down for restart.
     */
    boolean saveExpectedMapAtFreeze = false;

    /** query record while running */
    boolean queryRecord = false;

    /**
     * whether generate fixed size or varied size for random payload,
     * when enabled payload size is between payloadByteSize and maxByteSize
     */
    private int payloadByteSize = 100;
    /** default payload byte size */
    private int maxByteSize = 100;

    /**
     * If it is true, treat handling transactions on deleted entities of MerkleMap as info/warn
     */
    private boolean performOnDeleted = true;

    /**
     * If it is true, treat handling create transactions on entities that are already
     * existing in MerkleMap as info/warn
     */
    private boolean createOnExistingEntities = false;

    /**
     * If it is true, treat handling create transactions on entities that are already
     * existing in MerkleMap as info/warn
     */
    private boolean performOnNonExistingEntities = true;

    /**
     * If it is true, allow nodes performing operations only on the entities they created.
     * If it is false, each node can perform operation on entities any node created.
     */
    private boolean operateEntitiesOfSameNode = false;

    /**
     * When testing PTA with network errors, TYPE_TEST_PAUSE may get lost, this setting enable
     * PTA to continue to next stage instead of waiting forever.
     */
    private int allowedMissingPauseMessage;

    private PAYLOAD_TYPE type = PAYLOAD_TYPE.TYPE_RANDOM_BYTES;

    private PayloadDistribution distribution = null;

    private RandomDelayCfg delayCfg;

    /**
     * if payload type is TYPE_FCM_VIRTUAL_MIX, what percentage is FCM transactions, the remained is virtual merkle
     * transaction
     */
    private float ratioOfFCMTransaction;

    public PayloadCfgSimple() {
        // empty
    }

    private PayloadCfgSimple(final PayloadCfgSimple sourcePayload) {
        super(sourcePayload);
        setAppendSig(sourcePayload.appendSig);
        setInvalidSigRatio(sourcePayload.invalidSigRatio);
        setInsertSeq(sourcePayload.insertSeq);
        setVariedSize(sourcePayload.variedSize);
        setPayloadByteSize(sourcePayload.payloadByteSize);
        setMaxByteSize(sourcePayload.maxByteSize);
        setType(sourcePayload.type);
        setDistribution(sourcePayload.distribution);
        setDelayCfg(sourcePayload.delayCfg);
        setImmutable(false);
        sourcePayload.setImmutable(true);
    }

    public boolean isInsertSeq() {
        return insertSeq;
    }

    public void setInsertSeq(boolean insertSeq) {
        this.insertSeq = insertSeq;
    }

    public boolean isAppendSig() {
        return appendSig;
    }

    public void setAppendSig(boolean appendSig) {
        this.appendSig = appendSig;
    }

    public double getInvalidSigRatio() {
        return invalidSigRatio;
    }

    public void setInvalidSigRatio(double invalidSigRatio) {
        this.invalidSigRatio = invalidSigRatio;
    }

    public boolean isVariedSize() {
        return variedSize;
    }

    public void setVariedSize(boolean variedSize) {
        this.variedSize = variedSize;
    }

    public int getPayloadByteSize() {
        return payloadByteSize;
    }

    public void setPayloadByteSize(int payloadByteSize) {
        this.payloadByteSize = payloadByteSize;
    }

    public int getMaxByteSize() {
        return maxByteSize;
    }

    public void setMaxByteSize(int maxByteSize) {
        this.maxByteSize = maxByteSize;
    }

    public PAYLOAD_TYPE getType() {
        return type;
    }

    public void setType(PAYLOAD_TYPE type) {
        this.type = type;
    }

    public PayloadDistribution getDistribution() {
        return distribution;
    }

    public void setDistribution(PayloadDistribution distribution) {
        this.distribution = distribution;
    }

    public boolean isEnableCheck() {
        return enableCheck;
    }

    public void setEnableCheck(boolean enableCheck) {
        this.enableCheck = enableCheck;
    }

    public boolean isCheckFCM() {
        return checkFCM;
    }

    public RandomDelayCfg getDelayCfg() {
        return delayCfg;
    }

    public void setDelayCfg(RandomDelayCfg delayCfg) {
        this.delayCfg = delayCfg;
    }

    public int getProgressMarker() {
        return progressMarker;
    }

    public long getErrorThreshold() {
        return errorThreshold;
    }

    public boolean isContinueAfterError() {
        return continueAfterError;
    }

    public boolean isKeepCheckLog() {
        return keepCheckLog;
    }

    public void setKeepCheckLog(boolean keepCheckLog) {
        this.keepCheckLog = keepCheckLog;
    }

    public boolean isSaveAppLogState() {
        return saveAppLogState;
    }

    public void setSaveAppLogState(boolean saveAppLogState) {
        this.saveAppLogState = saveAppLogState;
    }

    public boolean isWaitForSaveStateDuringFreeze() {
        return waitForSaveStateDuringFreeze;
    }

    public void setWaitForSaveStateDuringFreeze(boolean waitForSaveStateDuringFreeze) {
        this.waitForSaveStateDuringFreeze = waitForSaveStateDuringFreeze;
    }

    public boolean isQueryRecord() {
        return queryRecord;
    }

    public boolean isSaveExpectedMapAtFreeze() {
        return saveExpectedMapAtFreeze;
    }

    public boolean isPerformOnDeleted() {
        return performOnDeleted;
    }

    public void setPerformOnDeleted(boolean performOnDeleted) {
        this.performOnDeleted = performOnDeleted;
    }

    public boolean isCreateOnExistingEntities() {
        return createOnExistingEntities;
    }

    public void setCreateOnExistingEntities(boolean createOnExistingEntities) {
        this.createOnExistingEntities = createOnExistingEntities;
    }

    public boolean isPerformOnNonExistingEntities() {
        return performOnNonExistingEntities;
    }

    public void setPerformOnNonExistingEntities(boolean performOnNonExistingEntities) {
        this.performOnNonExistingEntities = performOnNonExistingEntities;
    }

    public boolean isOperateEntitiesOfSameNode() {
        return operateEntitiesOfSameNode;
    }

    public int getAllowedMissingPauseMessage() {
        return allowedMissingPauseMessage;
    }

    public void setAllowedMissingPauseMessage(int allowedMissingPauseMessage) {
        this.allowedMissingPauseMessage = allowedMissingPauseMessage;
    }

    public int getFcqTtl() {
        return fcqTtl;
    }

    public void setFcqTtl(int fcqTtl) {
        this.fcqTtl = fcqTtl;
    }

    public boolean isCheckExpiration() {
        return checkExpiration;
    }

    public void setCheckExpiration(boolean checkExpiration) {
        this.checkExpiration = checkExpiration;
    }

    /**
     * Gets the ratio for FCM transactions.
     *
     * @return the radio for FCM transactions.
     */
    public float getRatioOfFCMTransaction() {
        return ratioOfFCMTransaction;
    }

    /**
     * Sets the ratio for FCM transactions.
     *
     * @param ratioOfFCMTransaction
     * 		The new ratio for FCM transactions.
     */
    public void setRatioOfFCMTransaction(final float ratioOfFCMTransaction) {
        this.ratioOfFCMTransaction = ratioOfFCMTransaction;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PayloadCfgSimple copy() {
        throwIfImmutable();
        return new PayloadCfgSimple(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeBoolean(this.insertSeq);
        out.writeBoolean(this.appendSig);
        out.writeBoolean(this.variedSize);
        out.writeInt(this.payloadByteSize);
        out.writeInt(this.maxByteSize);
        out.writeInt(this.type.ordinal());
        out.writeDouble(this.invalidSigRatio);
        out.writeSerializable(delayCfg, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        insertSeq = in.readBoolean();
        appendSig = in.readBoolean();
        variedSize = in.readBoolean();
        payloadByteSize = in.readInt();
        maxByteSize = in.readInt();
        type = PAYLOAD_TYPE.fromOrdinal(in.readInt());
        invalidSigRatio = in.readDouble();
        delayCfg = in.readSerializable();
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
