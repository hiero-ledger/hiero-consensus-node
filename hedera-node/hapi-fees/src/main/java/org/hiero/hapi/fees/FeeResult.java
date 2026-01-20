// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of calculating a transaction fee. The fee
 * is composed of three sub-fees for the node, network, and service.
 * All values are in tinycents.
 */
public class FeeResult {
    /** The service component in tinycents. */
    private long service = 0;

    private long serviceBase = 0;
    private List<FeeDetail> serviceExtras = new ArrayList<>();
    /** The node component in tinycents. */
    private long node = 0;

    /** Add a service fee with details.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addServiceFee(long count, long cost) {
        details.add(new FeeDetail(count, cost));
        service = clampedAdd(service, clampedMultiply(count, cost));
    private long nodeBase = 0;
    /** The node base component in Tiny Cents */
    public long getNodeBaseTC() {
        return this.nodeBase;
    }

    /** Add a node fee with details.
     * @param count the number of units for this fee.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addNodeFee(long count, long cost) {
        details.add(new FeeDetail(count, cost));
        node = clampedAdd(node, clampedMultiply(count, cost));
    }
    private List<FeeDetail> nodeExtras = new ArrayList<>();

    private int networkMultiplier = 0;
    private int congestionMultiplier = 1;

    /** Add the service base fee in tiny cents.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addServiceBaseTC(long cost) {
        serviceBase = cost;
        service = clampedAdd(service, cost);
    }

    public void addServiceExtra(String name, long per_unit, long used, int included, long charged) {
        serviceExtras.add(new FeeDetail(name, per_unit, used, included, charged));
        service = clampedAdd(service, per_unit * charged);
    }

    /** Add the node base fee in tiny cents.
     * @param cost the actual computed cost of this node base fee in tinycents.
     * */
    public void addNodeBaseTC(long cost) {
        nodeBase = cost;
        node = clampedAdd(node, cost);
    }

    /** Add node extra fee with details.
     *
     * @param name
     * @param per_unit
     * @param used
     * @param included
     * @param charged
     */
    public void addNodeExtra(String name, long per_unit, long used, int included, long charged) {
        nodeExtras.add(new FeeDetail(name, per_unit, used, included, charged));
        node = clampedAdd(node, per_unit * charged);
    }

    /** Set the network multiplier. This is the factor multiplied by the node fee to calculate the network fee
     *
     * @param multiplier new network multiplier
     */
    public void setNetworkMultiplier(int multiplier) {
        this.networkMultiplier = multiplier;
    }

    /** the total fee in tinycents. */
    public long totalTC() {
        return clampedAdd(clampedAdd(this.nodeTotalTC(), this.networkTotalTC()), this.service);
    }
    /** the total node fee in tinycents. */
    public long nodeTotalTC() {
        return this.node;
    }
    /** the total network fee in tinycents. */
    public long networkTotalTC() {
        return this.nodeTotalTC() * networkMultiplier;
    }
    /** the total service fee in tinycents. */
    public long serviceTotalTC() {
        return this.service;
    }

    public Iterable<? extends FeeDetail> getNodeExtras() {
        return this.nodeExtras;
    }

    public long getNetworkMultiplier() {
        return this.networkMultiplier;
    }

    public long getServiceBase() {
        return this.serviceBase;
    }

    public Iterable<? extends FeeDetail> getServiceExtras() {
        return this.serviceExtras;
    }

    public record FeeDetail(String name, long per_unit, long used, long included, long charged) {}

    /** Utility class representing the details of a particular fee component. */
    @Override
    public String toString() {
        return "FeeResult{" + "fee=" + this.totalTC()
                + ", nodeBase=" + nodeBase
                + ", nodeExtras=" + nodeExtras
                + ", networkMultiplier=" + networkMultiplier
                + ", serviceBase=" + serviceBase
                + ", serviceDetails=" + serviceExtras
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
