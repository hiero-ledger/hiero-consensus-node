// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of calculating a transaction fee. The fee
 * is composed of three sub-fees for the node, network, and service.
 * All values are in tinycents.
 */
public class FeeResult {
    private long serviceBase = 0;
    private final List<FeeDetail> serviceExtrasDetails = new ArrayList<>();
    private long serviceTotal = 0;
    private long nodeBase = 0;
    private final List<FeeDetail> nodeExtrasDetails = new ArrayList<>();
    private long nodeTotal = 0;
    private int networkMultiplier = 0;

    public FeeResult() {}

    public FeeResult(long serviceTotal, long nodeTotal, int networkMultiplier) {
        this.serviceTotal = serviceTotal;
        this.nodeTotal = nodeTotal;
        this.networkMultiplier = networkMultiplier;
    }

    /**
     * Get the total Service component in tiny cents.
     */
    public long getServiceTotalTinycents() {
        return serviceTotal;
    }

    /**
     * Set the Service baseFee in tiny cents.
     *
     * @param cost the base fee component of this service fee in tinycents.
     *
     */
    public void setServiceBaseFeeTinycents(long cost) {
        this.serviceBase = cost;
        this.serviceTotal = clampedAdd(serviceTotal, cost);
    }

    /**
     * Get the Service base fee in tiny cents.
     *
     * @return service base fee
     *
     */
    public long getServiceBaseFeeTinycents() {
        return this.serviceBase;
    }

    /** Add a Service extra fee in tiny cents.
     * @param name the name of this extra
     * @param unitCost the cost of a single extra in tiny cents.
     * @param used how many of the extra were used
     * @param included how many of the extra were included for free
     */
    public void addServiceExtraFeeTinycents(String name, long unitCost, long used, long included) {
        var charged = Math.max(0, used - included);
        if (charged > 0) {
            serviceExtrasDetails.add(new FeeDetail(name, unitCost, used, included, charged));
            serviceTotal = clampedAdd(serviceTotal, clampedMultiply(unitCost, charged));
        }
    }

    /**
     * Details about the service fee extras, broken down by label.
     */
    public List<FeeDetail> getServiceExtraDetails() {
        return this.serviceExtrasDetails;
    }

    /**
     * Get the total Node component of the fee in tinycents.
     */
    public long getNodeTotalTinycents() {
        return nodeTotal;
    }

    /**
     * Set the Node baseFee in tiny cents.
     *
     * @param cost the actual computed cost of this service fee in tinycents.
     *
     */
    public void setNodeBaseFeeTinycents(long cost) {
        this.nodeBase = cost;
        this.nodeTotal = clampedAdd(nodeTotal, cost);
    }

    /**
     * Get the Service base fee in tiny cents.
     *
     * @return service base fee
     *
     */
    public long getNodeBaseFeeTinycents() {
        return this.nodeBase;
    }

    /** Add a Node extra fee in tiny cents.
     * @param name the name of this extra
     * @param unitCost the cost of a single extra in tiny cents.
     * @param used how many of the extra were used
     * @param included how many of the extra were included for free
     */
    public void addNodeExtraFeeTinycents(String name, long unitCost, long used, long included) {
        var charged = Math.max(0, used - included);
        if (charged > 0) {
            nodeExtrasDetails.add(new FeeDetail(name, unitCost, used, included, charged));
            nodeTotal = clampedAdd(nodeTotal, clampedMultiply(unitCost, charged));
        }
    }
    /**
     * Details about the service fee extras, broken down by label.
     */
    public List<FeeDetail> getNodeExtraDetails() {
        return this.nodeExtrasDetails;
    }

    /** Set the network multiplier */
    public void setNetworkMultiplier(int networkMultiplier) {
        this.networkMultiplier = networkMultiplier;
    }
    /** Get the network multiplier */
    public int getNetworkMultiplier() {
        return this.networkMultiplier;
    }

    /**
     * Get the Network component in tiny cents. This will always
     * be a multiple of the Node fee
     */
    public long getNetworkTotalTinycents() {
        return clampedMultiply(this.getNodeTotalTinycents(), this.networkMultiplier);
    }

    public void clearFees() {
        this.serviceExtrasDetails.clear();
        this.serviceBase = 0;
        this.serviceTotal = 0;
        this.nodeExtrasDetails.clear();
        this.nodeBase = 0;
        this.nodeTotal = 0;
        this.networkMultiplier = 0;
    }

    /**
     * The total computed fee of this transaction (Service + Node + Network) in tiny cents.
     */
    public long totalTinycents() {
        return clampedAdd(
                clampedAdd(this.getNodeTotalTinycents(), this.getNetworkTotalTinycents()),
                this.getServiceTotalTinycents());
    }

    public record FeeDetail(String name, long perUnit, long used, long included, long charged) {}

    @Override
    public String toString() {
        return "FeeResult{" + "fee=" + this.totalTinycents() + ", details=" + getServiceExtraDetails() + '}';
    }
}
