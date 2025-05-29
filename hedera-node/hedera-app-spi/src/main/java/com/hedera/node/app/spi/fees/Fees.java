// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static java.util.Objects.requireNonNull;

import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the combination of node, network, and service fees.
 *
 * <p>NOTE: This is functionality equivalent to the "FeeObject" object defined in the hapi-utils module. That object
 * should be deprecated and removed as part of the rewrite of fees, post the modularized release. At that time, this
 * object will be used instead of "FeeObject".
 *
 * @param nodeFee The fee to be paid to the node that submitted the transaction to the network, or that handled the
 *                query. This must be non-negative. The sum of node, network, and service fees must be less than
 *                {@link Long#MAX_VALUE}.
 * @param networkFee The portion of the fee paid to all nodes for having handled consensus and gossiping of the
 *                   transaction. This must be non-negative. The sum of node, network, and service fees must be less
 *                   than {@link Long#MAX_VALUE}.
 * @param serviceFee The portion of the fee covering storage and other costs related to execution of the transaction.
 *                   This must be non-negative. The sum of node, network, and service fees must be less than
 *                   {@link Long#MAX_VALUE}.
 */
public record Fees(long nodeFee, long networkFee, long serviceFee, double usd) {
    /** A constant representing zero fees. */
    public static final Fees FREE = new Fees(0, 0, 0, 0);
    /**
     * A constant representing fees of 1 constant resource usage for each of the node, network, and service components.
     * This is useful when a fee is required, but the entity is not present in state to determine the actual fee.
     */
    public static final FeeData CONSTANT_FEE_DATA = FeeData.newBuilder()
            .setNodedata(FeeComponents.newBuilder().setConstant(1).build())
            .setNetworkdata(FeeComponents.newBuilder().setConstant(1).build())
            .setServicedata(FeeComponents.newBuilder().setConstant(1).build())
            .build();

    public Fees {
        // Validate the fee components are never negative.
        if (nodeFee < 0) throw new IllegalArgumentException("Node fees must be non-negative");
        if (networkFee < 0) throw new IllegalArgumentException("Network fees must be non-negative");
        if (serviceFee < 0) throw new IllegalArgumentException("Service fees must be non-negative");
        if (usd < 0) throw new IllegalArgumentException("New fees must be non-negative");
    }

    public Fees(long nodeFee, long networkFee, long serviceFee) {
        this(nodeFee, networkFee, serviceFee, 0);
    }

    /**
     * Returns true if there is nothing to charge for these fees.
     *
     * @return true if there is nothing to charge for these fees
     */
    public boolean nothingToCharge() {
        return nodeFee == 0 && networkFee == 0 && serviceFee == 0 && usd == 0;
    }

    /**
     * Returns this {@link Fees} with the service fee zeroed out. Used when the payer was willing and able
     * to cover at least the network fee; but not the node
     *
     * @return this {@link Fees} with the service fee zeroed out
     */
    public Fees withoutServiceComponent() {
        return new Fees(nodeFee, networkFee, 0, 0);
    }

    /**
     * Returns this {@link Fees} with the node fee replaced by the actually charged amount.
     *
     * @return this {@link Fees} with the node fee replaced by the actually charged amount
     */
    public Fees withChargedNodeComponent(final long fee) {
        return new Fees(fee, networkFee, serviceFee);
    }

    /**
     * Returns this {@link Fees} with the node fee and network fee zeroed out.
     * @return this {@link Fees} with the node fee and network fee zeroed out
     */
    public Fees onlyServiceComponent() {
        return new Fees(0, 0, serviceFee, 0);
    }

    /**
     * Computes and returns the total fee, which is the sum of the node, network, and service fees.
     * @return the total fee. Will be non-negative.
     */
    public long totalFee() {
        // Safely add the three components together, such that an overflow is detected. In practice this should never
        // happen, since the maximum number of tinybars is less than Long.MAX_VALUE.
        return Math.addExact(totalWithoutServiceFee(), serviceFee);
    }

    /**
     * Computes and returns the total without service fees.
     *
     * @return the total without service fees. Will be non-negative.
     */
    public long totalWithoutServiceFee() {
        return Math.addExact(nodeFee, networkFee);
    }

    /**
     * Computes and returns the total without node fees
     *
     * @return the total without node fees. Will be non-negative.
     */
    public long totalWithoutNodeFee() {
        return Math.addExact(networkFee, serviceFee);
    }

    /**
     * Return a builder for building a copy of this object. It will be pre-populated with all the data from this object.
     *
     * @return a pre-populated builder
     */
    public Builder copyBuilder() {
        return new Builder().nodeFee(nodeFee).networkFee(networkFee).serviceFee(serviceFee);
    }

    /**
     * Add the fees from another {@link Fees} object to this one, and return the result.
     * @param fees The fees to add to this one
     * @return a new {@link Fees} object with the sum of the fees
     */
    public Fees plus(@NonNull final Fees fees) {
        requireNonNull(fees);
        return new Fees(nodeFee + fees.nodeFee(), networkFee + fees.networkFee(), serviceFee + fees.serviceFee(), usd + fees.usd());
    }

    /**
     * A builder for {@link Fees} objects.
     */
    public static final class Builder {
        private long nodeFee;
        private long networkFee;
        private long serviceFee;
        private double newFee;

        /**
         * Set the node fee.
         * @param nodeFee The node fee, which must be non-negative
         * @return this builder instance
         */
        public Builder nodeFee(long nodeFee) {
            if (nodeFee < 0) throw new IllegalArgumentException("Node fees must be non-negative");
            this.nodeFee = nodeFee;
            return this;
        }

        /**
         * Set the network fee.
         * @param networkFee The network fee, which must be non-negative
         * @return this builder instance
         */
        public Builder networkFee(long networkFee) {
            if (networkFee < 0) throw new IllegalArgumentException("Network fees must be non-negative");
            this.networkFee = networkFee;
            return this;
        }

        /**
         * Set the service fee.
         * @param serviceFee The service fee, which must be non-negative
         * @return this builder instance
         */
        public Builder serviceFee(long serviceFee) {
            if (serviceFee < 0) throw new IllegalArgumentException("Service fees must be non-negative");
            this.serviceFee = serviceFee;
            return this;
        }

        public Builder newFee(double newFee) {
            System.out.println("returning the new fee " + newFee);
            this.newFee = newFee;
            return this;
        }

        /**
         * Build a {@link Fees} object from the data in this builder.
         * @return a {@link Fees} object
         */
        public Fees build() {
            return new Fees(nodeFee, networkFee, serviceFee, newFee);
        }
    }
}
