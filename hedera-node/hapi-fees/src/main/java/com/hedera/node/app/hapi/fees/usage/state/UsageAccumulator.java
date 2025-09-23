// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.state;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ACCOUNT_AMT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RECEIPT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_BODY_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_RECORD_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.HRS_DIVISOR;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.RECEIPT_STORAGE_TIME_SEC;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.pricing.ResourceProvider;
import com.hedera.node.app.hapi.fees.pricing.UsableResource;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * Accumulates an estimate of the resources used by a HAPI operation.
 *
 * <p>Resources are consumed by three service providers,
 *
 * <ol>
 *   <li>The network when providing gossip, consensus, and short-term storage of receipts.
 *   <li>The node when communicating with the client, performing prechecks, and submitting to the
 *       network.
 *   <li>The network when performing the logical service itself.
 * </ol>
 *
 * The key fact is that the estimated resource usage for all three service providers is a pure
 * function of the <b>same</b> base usage estimates, for eight types of resources:
 *
 * <ol>
 *   <li>Network capacity needed to submit an operation to the network. Units are {@code bpt}
 *       (“bytes per transaction”).
 *   <li>Network capacity needed to return information from memory in response to an operation.
 *       Units are {@code bpr} (“bytes per response).
 *   <li>Network capacity needed to return information from disk in response to an operation. Units
 *       are {@code sbpr} (“storage bytes per response”).
 *   <li>RAM needed to persist an operation’s effects on consensus state, for as long as such
 *       effects are visible. Units are {@code rbh} (“RAM byte-hours”).
 *   <li>Disk space needed to persist the operation’s effect on consensus state, for as long as such
 *       effects are visible. Units are {@code sbh} (“storage byte-hours”).
 *   <li>Computation needed to verify a Ed25519 cryptographic signature. Units are {@code vpt}
 *       (“verifications per transaction”).
 *   <li>Transferred value, in tinybars. Units are {@code tv} (“tinybar value”).
 *   <li>Computation needed for incremental execution of a Solidity smart contract. Units are {@code
 *       gas}.
 * </ol>
 */
public class UsageAccumulator {
    private static final long LONG_BASIC_TX_BODY_SIZE = BASIC_TX_BODY_SIZE;

    /* Captures how much signature verification work was done exclusively by the submitting node. */
    private long numPayerKeys;

    private long bpt;
    private long bpr;
    private long sbpr;
    private long vpt;
    private long gas;
    /* For storage resources, we use a finer-grained estimate in
     * units of seconds rather than hours, since expiration times
     * are given in seconds since the (consensus) epoch. */
    private long rbs;
    private long sbs;
    private long networkRbs;
    private long tv;

    public static UsageAccumulator fromGrpc(final FeeData usage) {
        final var into = new UsageAccumulator();

        /* Network */
        final var networkUsage = usage.getNetworkdata();
        into.setUniversalBpt(networkUsage.getBpt());
        into.setVpt(networkUsage.getVpt());
        into.setNetworkRbs(networkUsage.getRbh() * HRS_DIVISOR);

        /* Node */
        final var nodeUsage = usage.getNodedata();
        into.setNumPayerKeys(nodeUsage.getVpt());
        into.setBpr(nodeUsage.getBpr());
        into.setSbpr(nodeUsage.getSbpr());

        /* Service */
        final var serviceUsage = usage.getServicedata();
        into.setRbs(serviceUsage.getRbh() * HRS_DIVISOR);
        into.setSbs(serviceUsage.getSbh() * HRS_DIVISOR);
        into.setTv(into.getServiceTv());

        return into;
    }

    public void resetForTransaction(final BaseTransactionMeta baseMeta, final SigUsage sigUsage) {
        final int memoBytes = baseMeta.memoUtf8Bytes();
        final int numTransfers = baseMeta.numExplicitTransfers();

        gas = sbs = sbpr = tv = 0;

        bpr = INT_SIZE;
        vpt = sigUsage.numSigs();
        bpt = LONG_BASIC_TX_BODY_SIZE + memoBytes + sigUsage.sigsSize();
        rbs = RECEIPT_STORAGE_TIME_SEC * (BASIC_TX_RECORD_SIZE + memoBytes + BASIC_ACCOUNT_AMT_SIZE * numTransfers);

        networkRbs = RECEIPT_STORAGE_TIME_SEC * BASIC_RECEIPT_SIZE;
        numPayerKeys = sigUsage.numPayerKeys();
    }

    /**
     * This is only called in when calculating TokenMintUsage for non-fungible unique tokens,
     * because it only depends on the number of serials minted and doesn't need all other fields.
     * Resets all the fields in the accumulator.
     */
    public void reset() {
        numPayerKeys = 0;
        bpt = 0;
        bpr = 0;
        sbpr = 0;
        vpt = 0;
        gas = 0;
        rbs = 0;
        sbs = 0;
        networkRbs = 0;
        tv = 0;
    }

    /* Resource accumulator methods */
    public void addBpt(final long amount) {
        bpt += amount;
    }

    public void addBpr(final long amount) {
        bpr += amount;
    }

    public void addSbpr(final long amount) {
        sbpr += amount;
    }

    public void addVpt(final long amount) {
        vpt += amount;
    }

    public void addGas(final long amount) {
        gas += amount;
    }

    public void addRbs(final long amount) {
        rbs += amount;
    }

    public void addSbs(final long amount) {
        sbs += amount;
    }

    public void addNetworkRbs(final long amount) {
        networkRbs += amount;
    }

    /* Provider-scoped usage estimates (pure functions of the total resource usage) */
    /* -- NETWORK & NODE -- */
    public long getUniversalBpt() {
        return bpt;
    }

    /* -- NETWORK -- */
    public long getNetworkVpt() {
        return vpt;
    }

    public long getNetworkRbh() {
        return ESTIMATOR_UTILS.nonDegenerateDiv(networkRbs, HRS_DIVISOR);
    }

    /* -- NODE -- */
    public long getNodeBpr() {
        return bpr;
    }

    public long getNodeSbpr() {
        return sbpr;
    }

    public long getNodeVpt() {
        return numPayerKeys;
    }

    /* -- SERVICE -- */
    public long getServiceRbh() {
        return ESTIMATOR_UTILS.nonDegenerateDiv(rbs, HRS_DIVISOR);
    }

    public long getServiceSbh() {
        return ESTIMATOR_UTILS.nonDegenerateDiv(sbs, HRS_DIVISOR);
    }

    public long getServiceTv() {
        return tv;
    }

    public long get(final ResourceProvider provider, final UsableResource resource) {
        switch (provider) {
            case NETWORK:
                switch (resource) {
                    case BPT:
                        return getUniversalBpt();
                    case VPT:
                        return getNetworkVpt();
                    case RBH:
                        return getNetworkRbh();
                    case CONSTANT:
                        return 1L;
                    default:
                        return 0L;
                }
            case NODE:
                switch (resource) {
                    case BPT:
                        return getUniversalBpt();
                    case BPR:
                        return getNodeBpr();
                    case SBPR:
                        return getNodeSbpr();
                    case VPT:
                        return getNodeVpt();
                    case CONSTANT:
                        return 1L;
                    default:
                        return 0L;
                }
            case SERVICE:
                switch (resource) {
                    case RBH:
                        return getServiceRbh();
                    case SBH:
                        return getServiceSbh();
                    case CONSTANT:
                        return 1L;
                    default:
                        return 0L;
                }
        }
        return 0L;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("universalBpt", getUniversalBpt())
                .add("networkVpt", getNetworkVpt())
                .add("networkRbh", getNetworkRbh())
                .add("nodeBpr", getNodeBpr())
                .add("nodeSbpr", getNodeSbpr())
                .add("nodeVpt", getNodeVpt())
                .add("serviceSbh", getServiceSbh())
                .add("serviceRbh", getServiceRbh())
                .add("gas", getGas())
                .add("rbs", getRbs())
                .add("serviceTv", getServiceTv())
                .toString();
    }

    /* Helpers for test coverage */
    long getBpt() {
        return bpt;
    }

    long getBpr() {
        return bpr;
    }

    long getSbpr() {
        return sbpr;
    }

    long getVpt() {
        return vpt;
    }

    long getGas() {
        return gas;
    }

    long getRbs() {
        return rbs;
    }

    long getSbs() {
        return sbs;
    }

    long getNetworkRbs() {
        return networkRbs;
    }

    long getNumPayerKeys() {
        return numPayerKeys;
    }

    public void setNumPayerKeys(final long numPayerKeys) {
        this.numPayerKeys = numPayerKeys;
    }

    @Override
    public boolean equals(final Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    private void setUniversalBpt(final long bpt) {
        this.bpt = bpt;
    }

    private void setBpr(final long bpr) {
        this.bpr = bpr;
    }

    private void setSbpr(final long sbpr) {
        this.sbpr = sbpr;
    }

    private void setVpt(final long vpt) {
        this.vpt = vpt;
    }

    private void setRbs(final long rbs) {
        this.rbs = rbs;
    }

    private void setSbs(final long sbs) {
        this.sbs = sbs;
    }

    private void setTv(final long tv) {
        this.tv = tv;
    }

    private void setNetworkRbs(final long networkRbs) {
        this.networkRbs = networkRbs;
    }
}
