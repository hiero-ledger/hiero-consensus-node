// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.lifecycle;

import java.io.Serializable;
import java.util.Objects;

/**
 * A status in an entity's lifecycle
 */
public class LifecycleStatus implements Serializable {
    /**
     * A state of a transaction in its lifecycle
     */
    private TransactionState transactionState;
    /**
     * The type of a transaction
     */
    private TransactionType transactionType;
    /**
     * The timestamp of corresponding transactionState.
     * for INITIALIZED, it is the timestamp when initializing;
     * for SUBMITTED and SUBMISSION_FAILED, it is the timestamp when submitting;
     * for HANDLE* and *INVALID_SIG, it is the consensus timestamp when handling the transaction
     * for RECONNECT_ORIGIN and RESTART_ORIGIN, it is the consensus timestamp of signed state
     */
    private long timestamp;
    /**
     * Id of the node which initializes this transaction
     */
    private long nodeId;

    public LifecycleStatus(
            TransactionState transactionState, TransactionType transactionType, long timestamp, long nodeId) {
        this.transactionState = transactionState;
        this.transactionType = transactionType;
        this.timestamp = timestamp;
        this.nodeId = nodeId;
    }

    // Empty constructor is needed by jackson for deserialization
    public LifecycleStatus() {}

    private LifecycleStatus(LifecycleStatus.Builder builder) {
        this.transactionState = builder.transactionState;
        this.transactionType = builder.transactionType;
        this.timestamp = builder.timestamp;
        this.nodeId = builder.nodeId;
    }

    public TransactionState getTransactionState() {
        return transactionState;
    }

    public LifecycleStatus setTransactionState(TransactionState transactionState) {
        this.transactionState = transactionState;
        return this;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public LifecycleStatus setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
        return this;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public LifecycleStatus setTimestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public long getNodeId() {
        return nodeId;
    }

    public LifecycleStatus setNodeId(long nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    @Override
    public String toString() {
        return String.format(
                "TransactionState: %s, TransactionType: %s, " + "timestamp: %d, nodeId: %d",
                transactionState, transactionType, timestamp, nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionState, transactionType, timestamp, nodeId);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object != null && object instanceof LifecycleStatus) {
            LifecycleStatus that = (LifecycleStatus) object;
            return this.transactionState.equals(that.transactionState)
                    && this.transactionType.equals(that.transactionType)
                    && this.timestamp == that.timestamp
                    && this.nodeId == that.nodeId;
        }
        return false;
    }

    public static LifecycleStatus.Builder builder() {
        return new LifecycleStatus.Builder();
    }

    public static final class Builder {
        private TransactionState transactionState;
        private TransactionType transactionType;
        private long timestamp;
        private long nodeId = -1;

        private Builder() {}

        public LifecycleStatus.Builder setTransactionState(TransactionState transactionState) {
            this.transactionState = transactionState;
            return this;
        }

        public LifecycleStatus.Builder setTransactionType(TransactionType transactionType) {
            this.transactionType = transactionType;
            return this;
        }

        public LifecycleStatus.Builder setTimestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public LifecycleStatus.Builder setNodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public LifecycleStatus build() {
            return new LifecycleStatus(this);
        }
    }
}
