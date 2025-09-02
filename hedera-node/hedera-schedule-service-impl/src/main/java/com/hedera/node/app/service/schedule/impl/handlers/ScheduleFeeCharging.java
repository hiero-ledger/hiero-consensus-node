// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A fee charging strategy that delegates to the base fee charging strategy; but <b>only</b> for the service
 * component of the fees in each charging scenario.
 */
@Singleton
public class ScheduleFeeCharging implements FeeCharging {
    private final Supplier<FeeCharging> baseFeeCharging;

    @Inject
    public ScheduleFeeCharging(@NonNull final Supplier<FeeCharging> baseFeeCharging) {
        this.baseFeeCharging = requireNonNull(baseFeeCharging);
    }

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
        return baseFeeCharging
                .get()
                .validate(payer, creatorId, fees.onlyServiceComponent(), body, isDuplicate, function, category);
    }

    @Override
    public Fees charge(@NonNull final Context ctx, @NonNull final Validation validation, @NonNull final Fees fees) {
        requireNonNull(ctx);
        requireNonNull(validation);
        requireNonNull(fees);
        return baseFeeCharging.get().charge(ctx, validation, fees.onlyServiceComponent());
    }

    @Override
    public void refund(@NonNull final Context ctx, @NonNull final Fees fees) {
        requireNonNull(ctx);
        requireNonNull(fees);
        baseFeeCharging.get().refund(ctx, fees.onlyServiceComponent());
    }
}
