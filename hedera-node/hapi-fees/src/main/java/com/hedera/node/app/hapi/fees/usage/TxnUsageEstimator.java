// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.HRS_DIVISOR;

import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class TxnUsageEstimator {
    private final SigUsage sigUsage;
    private final TransactionBody txn;
    private final EstimatorUtils utils;

    private long bpt;
    private long vpt;
    private long rbs;
    private long sbs;
    private long gas;
    private long tv;
    private long networkRbs;

    public TxnUsageEstimator(SigUsage sigUsage, TransactionBody txn, EstimatorUtils utils) {
        this.txn = txn;
        this.utils = utils;
        this.sigUsage = sigUsage;
    }

    public FeeData get() {
        return get(SubType.DEFAULT);
    }

    public FeeData get(SubType subType) {
        var usage = utils.baseEstimate(txn, sigUsage);
        customize(usage);
        return utils.withDefaultTxnPartitioning(
                usage.build(), subType, utils.nonDegenerateDiv(networkRbs, HRS_DIVISOR), sigUsage.numPayerKeys());
    }

    private void customize(UsageEstimate usage) {
        var baseUsage = usage.base();
        baseUsage
                .setBpt(baseUsage.getBpt() + bpt)
                .setVpt(baseUsage.getVpt() + vpt)
                .setGas(baseUsage.getGas() + gas)
                .setTv(baseUsage.getTv() + tv);
        usage.addRbs(rbs);
        usage.addSbs(sbs);
        this.networkRbs += utils.baseNetworkRbs();
    }

    public TxnUsageEstimator addBpt(long bpt) {
        this.bpt += bpt;
        return this;
    }

    public TxnUsageEstimator addVpt(long vpt) {
        this.vpt += vpt;
        return this;
    }

    public TxnUsageEstimator addRbs(long rbs) {
        this.rbs += rbs;
        return this;
    }

    public TxnUsageEstimator addSbs(long sbs) {
        this.sbs += sbs;
        return this;
    }

    public TxnUsageEstimator addGas(long gas) {
        this.gas += gas;
        return this;
    }

    public TxnUsageEstimator addTv(long tv) {
        this.tv += tv;
        return this;
    }

    public TxnUsageEstimator addNetworkRbs(long networkRbs) {
        this.networkRbs += networkRbs;
        return this;
    }

    public SigUsage getSigUsage() {
        return sigUsage;
    }
}
