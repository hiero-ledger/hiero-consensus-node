// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A fee charging strategy that validates all scenarios and charges no fees.
 */
public enum NoopFeeCharging implements FeeCharging {
    NOOP_FEE_CHARGING;

    @Override
    public Validation validate(
            @NonNull final Account payer,
            @NonNull final AccountID creatorId,
            @NonNull final Fees fees,
            @NonNull final TransactionBody body,
            final boolean isDuplicate,
            @NonNull final HederaFunctionality function,
            @NonNull final HandleContext.TransactionCategory category) {
        requireNonNull(payer);
        requireNonNull(creatorId);
        requireNonNull(fees);
        requireNonNull(body);
        requireNonNull(function);
        requireNonNull(category);
        return PassedValidation.INSTANCE;
    }

    @Override
    public Fees charge(@NonNull final Context ctx, @NonNull final Validation validation, @NonNull final Fees fees) {
        requireNonNull(ctx);
        requireNonNull(validation);
        requireNonNull(fees);
        return Fees.FREE;
    }

    @Override
    public void refund(@NonNull final Context ctx, @NonNull final Fees fees) {
        requireNonNull(ctx);
        requireNonNull(fees);
        // No-op
    }

    private record PassedValidation(boolean creatorDidDueDiligence, @Nullable ResponseCodeEnum maybeErrorStatus)
            implements Validation {
        private static final PassedValidation INSTANCE = new PassedValidation(true, null);
    }
}
