// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.node.app.spi.workflows.QueryContext;

public class ServiceSimpleFeeContextImpl implements SimpleFeeContext {
    private final FeeContext feeContext;

    public ServiceSimpleFeeContextImpl(FeeContext feeContext) {
        this.feeContext = feeContext;
    }

    @Override
    public int numTxnSignatures() {
        return this.feeContext.numTxnSignatures();
    }

    @Override
    public int numTxnBytes() {
        return 0;
    }

    @Override
    public FeeContext feeContext() {
        return this.feeContext;
    }

    @Override
    public QueryContext queryContext() {
        return null;
    }

    @Override
    public EstimationMode estimationMode() {
        return EstimationMode.STATEFUL;
    }
}
