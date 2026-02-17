// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Simple implementation of {@link SimpleFeeContext} that wraps either a {@link FeeContext} or
 * a {@link QueryContext}.
 */
public final class SimpleFeeContextImpl implements SimpleFeeContext {
    @Nullable
    private final FeeContext feeContext;

    @Nullable
    private final QueryContext queryContext;

    public SimpleFeeContextImpl(@Nullable final FeeContext feeContext, @Nullable final QueryContext queryContext) {
        if (feeContext != null && queryContext != null) {
            throw new IllegalArgumentException("Only one of feeContext or queryContext may be set");
        }
        this.feeContext = feeContext;
        this.queryContext = queryContext;
    }

    @Override
    public int numTxnSignatures() {
        return feeContext == null ? 0 : feeContext.numTxnSignatures();
    }

    @Override
    public int numTxnBytes() {
        return feeContext == null ? 0 : feeContext.numTxnBytes();
    }

    @Override
    public HederaFunctionality functionality() {
        // This is used only for high volume transactions
        if (feeContext == null) {
            throw new UnsupportedOperationException("Not implemented for queries");
        }
        return requireNonNull(feeContext).functionality();
    }

    @Override
    public @Nullable FeeContext feeContext() {
        return feeContext;
    }

    @Override
    public @Nullable QueryContext queryContext() {
        return queryContext;
    }

    @Override
    public int getHighVolumeThrottleUtilization(@NonNull HederaFunctionality functionality) {
        if (feeContext == null) {
            throw new UnsupportedOperationException("Not implemented for queries");
        }
        return feeContext.getHighVolumeThrottleUtilization(functionality);
    }
}
