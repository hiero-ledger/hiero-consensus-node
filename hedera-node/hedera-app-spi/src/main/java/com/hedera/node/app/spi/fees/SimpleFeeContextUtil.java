// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.Nullable;

public class SimpleFeeContextUtil {
    public static SimpleFeeContext fromFeeContext(FeeContext feeContext) {
        return new FeeContextSFCImpl(feeContext);
    }

    public static SimpleFeeContext fromQueryContext(QueryContext queryContext) {
        return new QueryContextSFCImpl(queryContext);
    }

    private static class FeeContextSFCImpl implements SimpleFeeContext {
        private final FeeContext feeContext;

        public FeeContextSFCImpl(FeeContext feeContext) {
            this.feeContext = feeContext;
        }

        @Override
        public int numTxnSignatures() {
            return feeContext.numTxnSignatures();
        }

        @Override
        public int numTxnBytes() {
            return feeContext.numTxnBytes();
        }

        @Override
        public @Nullable FeeContext feeContext() {
            return this.feeContext;
        }

        @Override
        public @Nullable QueryContext queryContext() {
            return null;
        }
    }

    private static class QueryContextSFCImpl implements SimpleFeeContext {
        private final QueryContext queryContext;

        public QueryContextSFCImpl(QueryContext queryContext) {
            this.queryContext = queryContext;
        }

        @Override
        public int numTxnSignatures() {
            return 0;
        }

        @Override
        public int numTxnBytes() {
            return 0;
        }

        @Override
        public @Nullable FeeContext feeContext() {
            return null;
        }

        @Override
        public @Nullable QueryContext queryContext() {
            return this.queryContext;
        }
    }
}
