// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.util.Objects.requireNonNull;

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

    private SimpleFeeContextImpl(@Nullable final FeeContext feeContext, @Nullable final QueryContext queryContext) {
        this.feeContext = feeContext;
        this.queryContext = queryContext;
    }

    public static SimpleFeeContext fromFeeContext(@NonNull final FeeContext feeContext) {
        return new SimpleFeeContextImpl(requireNonNull(feeContext), null);
    }

    public static SimpleFeeContext fromQueryContext(@Nullable final QueryContext queryContext) {
        return new SimpleFeeContextImpl(null, queryContext);
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
    public @Nullable FeeContext feeContext() {
        return feeContext;
    }

    @Override
    public @Nullable QueryContext queryContext() {
        return queryContext;
    }
}
