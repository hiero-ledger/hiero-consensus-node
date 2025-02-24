// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/*
 * Use this config to set different payload generation mode
 */
public class PayloadConfig {

    // Pre-append this byte as a marker for all application transactions, so that
    // they are easily distinguishable from system transactions
    public static final byte APPLICATION_TRANSACTION_MARKER = 1;

    /**
     * use this for all logging
     */
    private static final Logger logger = LogManager.getLogger(PayloadConfig.class);

    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO");

    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");
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
     * if payload type is TYPE_FCM_VIRTUAL_MIX, what percentage is FCM transactions, the remained is virtual merkle
     * transaction
     */
    private final float ratioOfFCMTransaction;

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
     * If it is true, treat handling transactions on entities that doesn't exist
     * existing in MerkleMap as info/warn
     */
    private boolean performOnNonExistingEntities = true;

    /**
     * If it is true, allow nodes performing operations only on the entities they created.
     * If it is false, each node can perform operation on entities any node created.
     */
    private final boolean operateEntitiesOfSameNode = false;
    /**
     * when it is false, payload bytes size would be payloadByteSize;
     * when it is true, payload bytes size be a random number in range [payloadByteSize, maxByteSize]
     */
    private boolean variedSize = false;
    /**
     * whether generate fixed size or varied size for random payload,
     * when enabled payload size is between payloadByteSize and maxByteSize
     */
    private int payloadByteSize = 100;
    /** default payload byte size */
    private int maxByteSize = 100;

    private PAYLOAD_TYPE type = PAYLOAD_TYPE.TYPE_RANDOM_BYTES;

    private PayloadDistribution distribution = null;

    private PayloadConfig(final Builder builder) {
        this.insertSeq = builder.insertSeq;
        this.appendSig = builder.appendSig;
        this.variedSize = builder.variedSize;
        this.payloadByteSize = builder.payloadByteSize;
        this.maxByteSize = builder.maxByteSize;
        this.type = builder.type;
        this.distribution = builder.distribution;
        this.invalidSigRatio = builder.invalidSigRatio;
        this.createOnExistingEntities = builder.createOnExistingEntities;
        this.performOnDeleted = builder.performOnDeleted;
        this.performOnNonExistingEntities = builder.performOnNonExistingEntities;
        this.ratioOfFCMTransaction = builder.ratioOfFCMTransaction;
    }

    public boolean isInsertSeq() {
        return insertSeq;
    }

    public boolean isAppendSig() {
        return appendSig;
    }

    public PAYLOAD_TYPE getType() {
        return type;
    }

    public int getPayloadByteSize() {
        return payloadByteSize;
    }

    public boolean isVariedSize() {
        return variedSize;
    }

    public int getMaxByteSize() {
        return maxByteSize;
    }

    public PayloadDistribution getPayloadDistribution() {
        return distribution;
    }

    public double getInvalidSigRatio() {
        return invalidSigRatio;
    }

    public void setPerformOnDeleted(final boolean performOnDeleted) {
        this.performOnDeleted = performOnDeleted;
    }

    public boolean isPerformOnDeleted() {
        return performOnDeleted;
    }

    public boolean isCreateOnExistingEntities() {
        return createOnExistingEntities;
    }

    public boolean isPerformOnNonExistingEntities() {
        return performOnNonExistingEntities;
    }

    public boolean isOperateEntitiesOfSameNode() {
        return operateEntitiesOfSameNode;
    }

    /**
     * Gets the ratio for FCM transactions.
     *
     * @return the radio for FCM transactions.
     */
    public float getRatioOfFCMTransaction() {
        return ratioOfFCMTransaction;
    }

    public void display() {
        logger.info(MARKER, " insertSeq         = " + insertSeq);
        logger.info(MARKER, " appendSig         = " + appendSig);
        logger.info(MARKER, " variedSize        = " + variedSize);
        logger.info(MARKER, " payloadByteSize   = " + payloadByteSize);
        logger.info(MARKER, " maxByteSize       = " + maxByteSize);
        logger.info(MARKER, " type              = " + type);
        logger.info(MARKER, " invalidSigRatio   = " + invalidSigRatio);

        if (distribution != null) {
            distribution.display();
        }
    }

    // @Override
    // public PayloadConfig copy() {
    // return PayloadConfig.builder()
    // .setInsertSeq(this.insertSeq)
    // .setAppendSig(this.appendSig)
    // .setVariedSize(this.variedSize)
    // .setPayloadByteSize(this.payloadByteSize)
    // .setMaxByteSize(this.maxByteSize)
    // .setType(this.type)
    // .setDistribution(this.distribution)
    // .build();
    // }
    //
    // @Override
    // public void copyTo(FCDataOutputStream outStream) throws IOException {
    // outStream.writeBoolean( this.insertSeq);
    // outStream.writeBoolean( this.appendSig);
    // outStream.writeBoolean( this.variedSize);
    // outStream.writeInt( this.payloadByteSize );
    // outStream.writeInt( this.maxByteSize );
    // outStream.writeInt( this.type.ordinal() );
    // if (distribution!=null) distribution.copyTo(outStream);
    // }
    //
    // @Override
    // public void copyFrom(FCDataInputStream inStream) throws IOException {
    // this.insertSeq = inStream.readBoolean();
    // this.appendSig = inStream.readBoolean();
    // this.variedSize = inStream.readBoolean();
    // this.payloadByteSize = inStream.readInt();
    // this.maxByteSize = inStream.readInt();
    // this.type = PAYLOAD_TYPE.fromOrdinal( inStream.readInt() );
    // if (distribution!=null) distribution.copyFrom(null);
    // }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean insertSeq;
        private boolean appendSig;
        private boolean variedSize;
        private int payloadByteSize = 100;
        private int maxByteSize = 100;
        private PAYLOAD_TYPE type = PAYLOAD_TYPE.TYPE_RANDOM_BYTES;
        private PayloadDistribution distribution = null;
        private double invalidSigRatio = 0;
        private float ratioOfFCMTransaction = 0;

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
         * If it is true, treat handling transactions on entities that doesn't exist in
         * MerkleMap as info/warn
         */
        private boolean performOnNonExistingEntities = true;

        /**
         * If it is true, allow nodes performing operations only on the entities they created.
         * If it is false, each node can perform operation on entities any node created.
         */
        private boolean operateEntitiesOfSameNode = false;

        private Builder() {}

        public Builder setInsertSeq(final boolean insertSeq) {
            this.insertSeq = insertSeq;
            return this;
        }

        public Builder setAppendSig(final boolean appendSig) {
            this.appendSig = appendSig;
            return this;
        }

        public Builder setVariedSize(final boolean variedSize) {
            this.variedSize = variedSize;
            return this;
        }

        public Builder setPayloadByteSize(final int payloadByteSize) {
            this.payloadByteSize = payloadByteSize;
            return this;
        }

        public Builder setMaxByteSize(final int maxByteSize) {
            this.maxByteSize = maxByteSize;
            return this;
        }

        public Builder setType(final PAYLOAD_TYPE type) {
            this.type = type;
            return this;
        }

        public Builder setDistribution(final PayloadDistribution distribution) {
            this.distribution = distribution;
            return this;
        }

        public Builder setInvalidSigRatio(final double invalidSigRatio) {
            this.invalidSigRatio = invalidSigRatio;
            return this;
        }

        /**
         * Sets the ratio for FCM transactions.
         *
         * @param ratioOfFCMTransaction
         * 		The new ratio for FCM transactions.
         */
        public Builder setRatioOfFCMTransaction(final float ratioOfFCMTransaction) {
            this.ratioOfFCMTransaction = ratioOfFCMTransaction;
            return this;
        }

        public Builder setPerformOnDeleted(final boolean performOnDeleted) {
            this.performOnDeleted = performOnDeleted;
            return this;
        }

        public Builder setCreateOnExistingEntities(final boolean createOnExistingEntities) {
            this.createOnExistingEntities = createOnExistingEntities;
            return this;
        }

        public Builder setPerformOnNonExistingEntities(final boolean performOnNonExistingEntities) {
            this.performOnNonExistingEntities = performOnNonExistingEntities;
            return this;
        }

        public Builder setOperateEntitiesOfSameNode(final boolean operateEntitiesOfSameNode) {
            this.operateEntitiesOfSameNode = operateEntitiesOfSameNode;
            return this;
        }

        public PayloadConfig build() {
            return new PayloadConfig(this);
        }
    }
}
