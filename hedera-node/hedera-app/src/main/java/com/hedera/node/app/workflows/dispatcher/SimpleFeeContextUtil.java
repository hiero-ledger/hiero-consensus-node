// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.jspecify.annotations.Nullable;

public class SimpleFeeContextUtil {
    public static SimpleFeeContext fromFeeContext(FeeContext feeContext) {
        return new FeeContextSFCImpl(feeContext);
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
            return 0;
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
}
