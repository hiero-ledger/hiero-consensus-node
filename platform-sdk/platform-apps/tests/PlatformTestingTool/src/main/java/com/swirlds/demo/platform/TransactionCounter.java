// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.common.FastCopyable;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * Statistic counter for a node to count different transaction type
 */
public class TransactionCounter implements Cloneable, FastCopyable, SelfSerializable {

    //    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");

    /**
     * The version history of this class.
     * Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        private static final int ORIGINAL = 1;
        private static final int REMOVE_FCLL_COUNTERS = 2;
        private static final int MIGRATE_TO_SERIALIZABLE = 3;
        private static final int REMOVE_FILE_COUNTERS = 4;
        private static final int ADD_VM_COUNTERS = 5;
    }

    private static final long CLASS_ID = 0x529845fd094b9fe3L;

    private static final Logger logger = LogManager.getLogger(TransactionCounter.class);

    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker STAT_MARKER = MarkerManager.getMarker("DEMO_STAT");

    public long fcmCreateAmount = 0;
    public long fcmUpdateAmount = 0;
    public long fcmDeleteAmount = 0;
    public long fcmTransferAmount = 0;
    public long fcmAssortedAmount = 0;

    public long fcmFCQAssortedAmount = 0;
    public long fcmFCQCreateAmount = 0;
    public long fcmFCQUpdateAmount = 0;
    public long fcmFCQDeleteAmount = 0;
    public long fcmFCQTransferAmount = 0;
    public long vmCreateAmount = 0;
    public long vmDeleteAmount = 0;
    public long vmUpdateAmount = 0;
    public long vmContractCreateAmount = 0;
    public long vmContractExecutionAmount = 0;

    private int nodeId;
    /** total FCM transactions from previous runs before restart */
    public long totalTranAmountFromPrevRun;

    private boolean immutable;

    long getTotalFCMTransactionAmount() {
        return fcmCreateAmount
                + fcmUpdateAmount
                + fcmDeleteAmount
                + fcmTransferAmount
                + fcmAssortedAmount
                + fcmFCQCreateAmount
                + fcmFCQUpdateAmount
                + fcmFCQDeleteAmount
                + fcmFCQTransferAmount
                + fcmFCQAssortedAmount
                + vmCreateAmount
                + vmDeleteAmount
                + vmUpdateAmount
                + vmContractCreateAmount
                + vmContractExecutionAmount;
    }

    long getAllTransactionAmount() {
        return getTotalFCMTransactionAmount();
    }

    public TransactionCounter(int nodeId) {
        this.nodeId = nodeId;
    }

    public TransactionCounter() {}

    private TransactionCounter(final TransactionCounter sourceTransactionCounter) {
        this.fcmCreateAmount = sourceTransactionCounter.fcmCreateAmount;
        this.fcmUpdateAmount = sourceTransactionCounter.fcmUpdateAmount;
        this.fcmDeleteAmount = sourceTransactionCounter.fcmDeleteAmount;
        this.fcmTransferAmount = sourceTransactionCounter.fcmTransferAmount;
        this.fcmAssortedAmount = sourceTransactionCounter.fcmAssortedAmount;

        this.fcmFCQCreateAmount = sourceTransactionCounter.fcmFCQCreateAmount;
        this.fcmFCQUpdateAmount = sourceTransactionCounter.fcmFCQUpdateAmount;
        this.fcmFCQDeleteAmount = sourceTransactionCounter.fcmFCQDeleteAmount;
        this.fcmFCQTransferAmount = sourceTransactionCounter.fcmFCQTransferAmount;
        this.fcmFCQAssortedAmount = sourceTransactionCounter.fcmFCQAssortedAmount;

        this.vmCreateAmount = sourceTransactionCounter.vmCreateAmount;
        this.vmDeleteAmount = sourceTransactionCounter.vmDeleteAmount;
        this.vmUpdateAmount = sourceTransactionCounter.vmUpdateAmount;
        this.vmContractCreateAmount = sourceTransactionCounter.vmContractCreateAmount;
        this.vmContractExecutionAmount = sourceTransactionCounter.vmContractExecutionAmount;

        this.nodeId = sourceTransactionCounter.nodeId;
        this.totalTranAmountFromPrevRun = sourceTransactionCounter.totalTranAmountFromPrevRun;

        this.immutable = false;
        sourceTransactionCounter.immutable = true;
    }

    @Override
    public TransactionCounter copy() {
        return new TransactionCounter(this);
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "TransactionCounter{" + "fcmCreateAmount="
                + fcmCreateAmount + ", fcmUpdateAmount="
                + fcmUpdateAmount + ", fcmDeleteAmount="
                + fcmDeleteAmount + ", fcmTransferAmount="
                + fcmTransferAmount + ", fcmAssortedAmount="
                + fcmAssortedAmount + ", fcmFCQCreateAmount="
                + fcmFCQCreateAmount + ", fcmFCQUpdateAmount="
                + fcmFCQUpdateAmount + ", fcmFCQDeleteAmount="
                + fcmFCQDeleteAmount + ", fcmFCQTransferAmount="
                + fcmFCQTransferAmount + ", fcmFCQAssortedAmount="
                + fcmFCQAssortedAmount + ", vmCreateAmount="
                + vmCreateAmount + ", vmDeleteAmount="
                + vmDeleteAmount + ", vmUpdateAmount="
                + vmUpdateAmount + ", vmContractCreateAmount="
                + vmContractCreateAmount + ", vmContractExecutionAmount="
                + vmContractExecutionAmount + ", totalTranAmountFromPrevRun="
                + totalTranAmountFromPrevRun + '}';
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeInt(nodeId);

        out.writeLong(fcmCreateAmount);
        out.writeLong(fcmUpdateAmount);
        out.writeLong(fcmDeleteAmount);
        out.writeLong(fcmTransferAmount);
        out.writeLong(fcmAssortedAmount);

        out.writeLong(fcmFCQCreateAmount);
        out.writeLong(fcmFCQUpdateAmount);
        out.writeLong(fcmFCQDeleteAmount);
        out.writeLong(fcmFCQTransferAmount);
        out.writeLong(fcmFCQAssortedAmount);

        out.writeLong(vmCreateAmount);
        out.writeLong(vmDeleteAmount);
        out.writeLong(vmUpdateAmount);
        out.writeLong(vmContractCreateAmount);
        out.writeLong(vmContractExecutionAmount);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        nodeId = in.readInt();
        if (version < ClassVersion.REMOVE_FILE_COUNTERS) {
            // counters for removed file features
            in.readLong();
            in.readLong();
            in.readLong();
            in.readLong();
            in.readLong();
            in.readLong();
        }
        fcmCreateAmount = in.readLong();
        fcmUpdateAmount = in.readLong();
        fcmDeleteAmount = in.readLong();
        fcmTransferAmount = in.readLong();
        fcmAssortedAmount = in.readLong();

        fcmFCQCreateAmount = in.readLong();
        fcmFCQUpdateAmount = in.readLong();
        fcmFCQDeleteAmount = in.readLong();
        fcmFCQTransferAmount = in.readLong();
        fcmFCQAssortedAmount = in.readLong();

        if (version >= ClassVersion.ADD_VM_COUNTERS) {
            vmCreateAmount = in.readLong();
            vmDeleteAmount = in.readLong();
            vmUpdateAmount = in.readLong();
            vmContractCreateAmount = in.readLong();
            vmContractExecutionAmount = in.readLong();
        }

        totalTranAmountFromPrevRun = getAllTransactionAmount();

        logger.info(MARKER, "Restore node {} state fcmCreateAmount {}", nodeId, fcmCreateAmount);
        logger.info(MARKER, "Restore node {} state fcmUpdateAmount {}", nodeId, fcmUpdateAmount);
        logger.info(MARKER, "Restore node {} state fcmDeleteAmount {}", nodeId, fcmDeleteAmount);
        logger.info(MARKER, "Restore node {} state fcmTransferAmount {}", nodeId, fcmTransferAmount);
        logger.info(MARKER, "Restore node {} state fcmAssortedAmount {}", nodeId, fcmAssortedAmount);

        logger.info(MARKER, "Restore node {} state fcmFCQCreateAmount {}", nodeId, fcmFCQCreateAmount);
        logger.info(MARKER, "Restore node {} state fcmFCQUpdateAmount {}", nodeId, fcmFCQUpdateAmount);
        logger.info(MARKER, "Restore node {} state fcmFCQDeleteAmount {}", nodeId, fcmFCQDeleteAmount);
        logger.info(MARKER, "Restore node {} state fcmFCQTransferAmount {}", nodeId, fcmFCQTransferAmount);
        logger.info(MARKER, "Restore node {} state fcmFCQAssortedAmount {}", nodeId, fcmFCQAssortedAmount);

        logger.info(MARKER, "Restore node {} state vmCreateAmount {}", nodeId, vmCreateAmount);
        logger.info(MARKER, "Restore node {} state vmDeleteAmount {}", nodeId, vmDeleteAmount);
        logger.info(MARKER, "Restore node {} state vmUpdateAmount {}", nodeId, vmUpdateAmount);
        logger.info(MARKER, "Restore node {} state vmContractCreateAmount {}", nodeId, vmContractCreateAmount);
        logger.info(MARKER, "Restore node {} state vmContractExecutionAmount {}", nodeId, vmContractExecutionAmount);

        logger.info(MARKER, "Restore state totalTranAmountFromPrevRun {}/{}", nodeId, totalTranAmountFromPrevRun);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ADD_VM_COUNTERS;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return this.immutable;
    }
}
