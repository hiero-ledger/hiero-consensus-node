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

    public long serviceBase = 0;
    public List<FeeDetail> serviceExtras = new ArrayList<>();
    /** The node component in tinycents. */
    public long node = 0;

    public long nodeBase = 0;
    public List<FeeDetail> nodeExtras = new ArrayList<>();
    /** The network component in tinycents. */
    public long network = 0;

    public int networkMultiplier = 0;

    /** Add a service fee with details.
     * @param cost the actual computed cost of this service fee in tinycents.
     * */
    public void addServiceBase(long cost) {
        serviceBase = cost;
        service = clampedAdd(service, cost);
    }

    public void addServiceExtra(String name, long per_unit, long used, int included, long charged) {
        serviceExtras.add(new FeeDetail(name, per_unit, used, included, charged));
        service = clampedAdd(service, per_unit * charged);
    }

    /** Add the node base fee with details.
     * @param cost the actual computed cost of this node base fee in tinycents.
     * */
    public void addNodeBase(long cost) {
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

    /** Add a network fee with details.
     * @param multiplier the network multiplier
     * @param node the actual computed cost of the node fee in tinycents
     * */
    public void addNetworkFee(int multiplier, long node) {
        networkMultiplier = multiplier;
        network = clampedAdd(network, node * multiplier);
    }

    /** the total fee in tinycents. */
    public long total() {
        return clampedAdd(clampedAdd(this.node, this.network), this.service);
    }

    public record FeeDetail(String name, long per_unit, long used, long included, long charged) {}

    /** Utility class representing the details of a particular fee component. */
    @Override
    public String toString() {
        return "FeeResult{" + "fee=" + this.total()
                + ", nodeBase=" + nodeBase
                + ", nodeExtras=" + nodeExtras
                + ", network=" + network
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
