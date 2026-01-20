// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.node.app.spi.workflows.QueryContext;

public class QuerySimpleFeeContextImpl implements SimpleFeeContext {
    private final QueryContext queryContext;

    public QuerySimpleFeeContextImpl(QueryContext queryContext) {
        this.queryContext = queryContext;
    }

    @Override
    public int numTxnSignatures() {
        return 0; // queries never have signatures
    }

    @Override
    public int numTxnBytes() {
        return 0;
    }

    @Override
    public FeeContext feeContext() {
        return null;
    }

    @Override
    public QueryContext queryContext() {
        return this.queryContext;
    }

}
