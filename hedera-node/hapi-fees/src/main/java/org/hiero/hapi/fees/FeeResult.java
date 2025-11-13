// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import java.util.LinkedHashMap;
import java.util.Map;

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
    public Map<String, FeeDetail> details = new LinkedHashMap<>();

    /** Add a service fee with details.
     * @param label a human-readable text description of the fee.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addServiceFee(String label, long count, long cost) {
        details.put(label, new FeeDetail(count, cost));
        service = clampedAdd(service, cost);
    }

    /** Add a node fee with details.
     * @param label a human-readable text description of the fee.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addNodeFee(String label, long count, long cost) {
        details.put(label, new FeeDetail(count, cost));
        node = clampedAdd(node, cost);
    }

    /** Add a network fee with details.
     * @param label a human-readable text description of the fee.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addNetworkFee(String label, long count, long cost) {
        details.put(label, new FeeDetail(count, cost));
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

        public FeeDetail(long count, long fee) {
            this.count = count;
            this.fee = fee;
        }

        @Override
        public String toString() {
            return "FeeDetail{" + this.count + ", " + this.fee + "}";
        }
    }

    @Override
    public String toString() {
        return "FeeResult{" + "fee=" + this.total() + ", details=" + details + '}';
    }

    private static long clampedAdd(final long a, final long b) {
        try {
            return Math.addExact(a, b);
        } catch (final ArithmeticException ae) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
