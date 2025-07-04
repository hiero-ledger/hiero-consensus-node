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
import java.util.function.ObjLongConsumer;

/**
 * A strategy for validating and charging fees for a transaction.
 */
public interface FeeCharging {
    /**
     * The result of validating a charging scenario.
     */
    interface Validation {
        /**
         * Whether the creator of the transaction did its due diligence on the solvency and
         * willingness of the payer account in the charging scenario.
         */
        boolean creatorDidDueDiligence();

        /**
         * If not null, the highest-priority error found when validating the charging scenario.
         */
        @Nullable
        ResponseCodeEnum maybeErrorStatus();

        /**
         * Returns the error status or throws an exception if the error status is null.
         */
        default ResponseCodeEnum errorStatusOrThrow() {
            return requireNonNull(maybeErrorStatus());
        }
    }

    /**
     * Validates the given charging scenario.
     * @param payer the account that will be charged
     * @param creatorId the account that created the transaction
     * @param fees the fees to be charged
     * @param body the transaction to be charged
     * @param isDuplicate whether the transaction is a duplicate
     * @param function the functionality being charged for
     * @param category the category of the transaction
     * @return the result of the validation
     */
    Validation validate(
            @NonNull Account payer,
            @NonNull AccountID creatorId,
            @NonNull Fees fees,
            @NonNull TransactionBody body,
            boolean isDuplicate,
            @NonNull HederaFunctionality function,
            @Deprecated @NonNull HandleContext.TransactionCategory category);

    /**
     * A context in which fees may actually be charged.
     */
    interface Context {
        /**
         * The primary payer account id for the context.
         * @return the payer account id
         */
        AccountID payerId();

        /**
         * The node fee collection account id for the context.
         * @return the node account id
         */
        AccountID nodeAccountId();

        /**
         * Charges the given amount to the given account, not disbursing any portion of the
         * collected fees to a node account.
         *
         * @param payerId the account to be charged
         * @param fees the fees to be charged
         * @param cb if not null, a callback to accept fee disbursements
         * @return the total fees charged
         */
        Fees charge(@NonNull AccountID payerId, @NonNull Fees fees, @Nullable ObjLongConsumer<AccountID> cb);

        /**
         * Charges the given amount to the given account, disbursing the currently configured
         * fraction of collected fees to the given node account.
         *
         * @param payerId the account to be charged
         * @param fees the fees to be charged
         * @param nodeAccountId the account to which a portion of the fees will be disbursed
         * @param cb if not null, a callback to accept fee disbursements
         * @return the total fees charged
         */
        Fees charge(
                @NonNull AccountID payerId,
                @NonNull Fees fees,
                @NonNull AccountID nodeAccountId,
                @Nullable ObjLongConsumer<AccountID> cb);

        /**
         * Refunds the given fees to the given account from the given node account (and, implicitly,
         * the fee collection accounts active for this transaction).
         *
         * @param payerId the account to be refunded
         * @param fees the fees to be refunded
         * @param nodeAccountId the account from which the fees will be refunded
         */
        void refund(@NonNull AccountID payerId, @NonNull Fees fees, @NonNull AccountID nodeAccountId);

        /**
         * Refunds the given fees to the given account from the the fee collection accounts
         * active for this transaction.
         *
         * @param receiverId the account to be refunded
         * @param fees the fees to be refunded
         */
        void refund(@NonNull AccountID receiverId, @NonNull Fees fees);

        /**
         * The category of the transaction in the charging scenario.
         */
        @Deprecated
        HandleContext.TransactionCategory category();
    }

    /**
     * Charges the fees for the given validation in the given context.
     *
     * @param ctx the context in which fees may be charged
     * @param validation the validation of the charging scenario
     * @param fees the fees to be charged
     * @return the total fees charged
     */
    Fees charge(@NonNull Context ctx, @NonNull Validation validation, @NonNull Fees fees);

    /**
     * Refunds the fees for the given validation in the given context.
     * @param ctx the context in which fees may be refunded
     * @param fees the fees to be refunded
     */
    void refund(@NonNull Context ctx, @NonNull Fees fees);
}
