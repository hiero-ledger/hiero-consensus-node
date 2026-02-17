// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_MULTIPLIER_SCALE;

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
    private long highVolumeMultiplier = HIGH_VOLUME_MULTIPLIER_SCALE;

    public FeeResult() {}

    public FeeResult(long serviceTotal, long nodeTotal, int networkMultiplier) {
        this.serviceTotal = serviceTotal;
        this.nodeTotal = nodeTotal;
        this.networkMultiplier = networkMultiplier;
        this.highVolumeMultiplier = HIGH_VOLUME_MULTIPLIER_SCALE;
    }

    /**
     * Get the total Service component in tiny cents.
     *
     * @return the total service fee in tiny cents
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

    /**
     * Add a Service extra fee in tiny cents.
     *
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
     * Applies a multiplier to the service and node totals using a fixed-point scale.
     *
     * @param rawMultiplier the multiplier value in fixed-point form
     * @param scale the fixed-point scale (e.g. 1000 for 3 decimal places)
     */
    public void applyMultiplier(final long rawMultiplier, final long scale) {
        if (rawMultiplier <= 0 || scale <= 0) {
            throw new IllegalArgumentException(
                    "Multiplier and scale must be positive (multiplier=" + rawMultiplier + ", scale=" + scale + ")");
        }
        if (rawMultiplier == scale) {
            return;
        }
        highVolumeMultiplier = rawMultiplier;
        serviceBase = scaleAmount(serviceBase, rawMultiplier, scale);
        nodeBase = scaleAmount(nodeBase, rawMultiplier, scale);
        scaleFeeDetails(serviceExtrasDetails, rawMultiplier, scale);
        scaleFeeDetails(nodeExtrasDetails, rawMultiplier, scale);
        recomputeTotals();
    }

    /**
     * Details about the service fee extras, broken down by label.
     *
     * @return the service extra details
     */
    public List<FeeDetail> getServiceExtraDetails() {
        return this.serviceExtrasDetails;
    }

    /**
     * Get the total Node component of the fee in tinycents.
     *
     * @return the total node fee in tiny cents
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

    /**
     * Add a Node extra fee in tiny cents.
     *
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
     *
     * @return the node extra details
     */
    public List<FeeDetail> getNodeExtraDetails() {
        return this.nodeExtrasDetails;
    }

    /**
     * Set the network multiplier
     *
     * @param networkMultiplier the network multiplier
     */
    public void setNetworkMultiplier(int networkMultiplier) {
        this.networkMultiplier = networkMultiplier;
    }

    /**
     * Get the network multiplier
     *
     * @return the network multiplier
     */
    public int getNetworkMultiplier() {
        return this.networkMultiplier;
    }

    /**
     * Get the Network component in tiny cents. This will always
     * be a multiple of the Node fee
     *
     * @return the network fee in tiny cents
     */
    public long getNetworkTotalTinycents() {
        return clampedMultiply(this.getNodeTotalTinycents(), this.networkMultiplier);
    }

    public long getHighVolumeMultiplier() {
        return highVolumeMultiplier;
    }

    public void clearFees() {
        this.serviceExtrasDetails.clear();
        this.serviceBase = 0;
        this.serviceTotal = 0;
        this.nodeExtrasDetails.clear();
        this.nodeBase = 0;
        this.nodeTotal = 0;
        this.networkMultiplier = 0;
        this.highVolumeMultiplier = HIGH_VOLUME_MULTIPLIER_SCALE;
    }

    /**
     * The total computed fee of this transaction (Service + Node + Network) in tiny cents.
     *
     * @return the total fee in tiny cents
     */
    public long totalTinycents() {
        return clampedAdd(
                clampedAdd(this.getNodeTotalTinycents(), this.getNetworkTotalTinycents()),
                this.getServiceTotalTinycents());
    }

    /**
     * Scales the given amount by the given multiplier.
     */
    private static long scaleAmount(final long amount, final long rawMultiplier, final long scale) {
        return clampedMultiply(amount, rawMultiplier) / scale;
    }
    /**
     * Scales the per-unit cost of each fee detail by the given multiplier.
     */
    private static void scaleFeeDetails(final List<FeeDetail> details, final long rawMultiplier, final long scale) {
        if (details.isEmpty()) {
            return;
        }
        final var scaled = new ArrayList<FeeDetail>(details.size());
        for (final var detail : details) {
            scaled.add(new FeeDetail(
                    detail.name(),
                    scaleAmount(detail.perUnit(), rawMultiplier, scale),
                    detail.used(),
                    detail.included(),
                    detail.charged()));
        }
        details.clear();
        details.addAll(scaled);
    }
    /**
     * Recomputes the totals based on the base fees and extra details.
     */
    private void recomputeTotals() {
        long serviceSum = serviceBase;
        for (final var detail : serviceExtrasDetails) {
            serviceSum = clampedAdd(serviceSum, clampedMultiply(detail.perUnit(), detail.charged()));
        }
        serviceTotal = serviceSum;

        long nodeSum = nodeBase;
        for (final var detail : nodeExtrasDetails) {
            nodeSum = clampedAdd(nodeSum, clampedMultiply(detail.perUnit(), detail.charged()));
        }
        nodeTotal = nodeSum;
    }
    /**
     * Details about a fee extra, including the name, per-unit cost, and how many were used and included.
     * @param charged how many of the extra were charged
     * @param included how many of the extra were included for free
     * @param name the name of this extra
     * @param perUnit the cost of a single extra in tiny cents.
     * @param used how many of the extra were used
     */
    public record FeeDetail(String name, long perUnit, long used, long included, long charged) {}

    @Override
    public String toString() {
        return "FeeResult{" + "totalFee=" + this.totalTinycents() + ", serviceBaseFee="
                + getServiceBaseFeeTinycents() + ", serviceDetails="
                + getServiceExtraDetails() + ", nodeBaseFee="
                + getNodeBaseFeeTinycents() + ", nodeDetails="
                + getNodeExtraDetails() + ", networkMultiplier="
                + getNetworkMultiplier() + ", networkFee="
                + getNetworkTotalTinycents() + ", highVolumeMultiplier="
                + getNetworkTotalTinycents() + '}';
    }
}
