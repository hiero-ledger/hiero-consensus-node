// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of calculating a transaction fee. The fee
 * is composed of three sub-fees for the node, network, and service.
 * All values are in tinycents.
 */
public class FeeResult {
    /** The service component in tinycents. */
    public long service = 0;
    /** The node component in tinycents. */
    public long node = 0;
    /** The network component in tinycents. */
    public long network = 0;
    /** Details about the fee, broken down by label. */
    public List<FeeDetail> nodeDetails = new ArrayList<>();
    public List<FeeDetail> networkDetails = new ArrayList<>();
    public List<FeeDetail> serviceDetails = new ArrayList<>();

    /** Add a service fee with details.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addServiceFee(long count, long cost) {
        serviceDetails.add(new FeeDetail(count, cost));
        service = clampedAdd(service, count * cost);
    }

    public void addServiceFee(long count, long cost, String name) {
        serviceDetails.add(new FeeDetail(count, cost, name));
        service = clampedAdd(service, count * cost);
    }

    /** Add a node fee with details.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addNodeFee(long count, long cost) {
        nodeDetails.add(new FeeDetail(count, cost));
        node = clampedAdd(node, count * cost);
    }
    public void addNodeFee(long count, long cost, String name) {
        nodeDetails.add(new FeeDetail(count, cost, name));
        node = clampedAdd(node, count * cost);
    }

    /** Add a network fee with details.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addNetworkFee(long cost) {
        networkDetails.add(new FeeDetail(1, cost));
        network = clampedAdd(network, cost);
    }

    /** the total fee in tinycents. */
    public long total() {
        return clampedAdd(clampedAdd(this.node, this.network), this.service);
    }

    /** Utility class representing the details of a particular fee component. */
    public static class FeeDetail {
        public long count;
        public long fee;
        public String name;

        public FeeDetail(long count, long fee) {
            this.count = count;
            this.fee = fee;
            this.name = "unnamed";
        }
        public FeeDetail(long count, long fee, String name) {
            this.count = count;
            this.fee = fee;
            this.name = name;
        }

        @Override
        public String toString() {
            return "FeeDetail{" + this.name + ", " + this.count + ", " + this.fee + "}";
        }
    }

    @Override
    public String toString() {
        return "FeeResult{" + "fee=" + this.total()
                + ", nodeDetails=" + nodeDetails
                + ", networkDetails=" + networkDetails
                + ", serviceDetails=" + serviceDetails
                + '}';
    }

    private static long clampedAdd(final long a, final long b) {
        try {
            return Math.addExact(a, b);
        } catch (final ArithmeticException ae) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
