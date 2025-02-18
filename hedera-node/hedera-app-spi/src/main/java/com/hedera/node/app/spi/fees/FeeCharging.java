/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
         * Charges the given amount to the given account, not disbursing any portion of the
         * collected fees to a node account.
         * @param payerId the account to be charged
         * @param fees the fees to be charged
         */
        void charge(@NonNull AccountID payerId, @NonNull Fees fees);

        /**
         * Charges the given amount to the given account, disbursing the currently configured
         * fraction of collected fees to the given node account.
         * @param payerId the account to be charged
         * @param fees the fees to be charged
         * @param nodeAccountId the account to which a portion of the fees will be disbursed
         */
        void charge(@NonNull AccountID payerId, @NonNull Fees fees, @NonNull AccountID nodeAccountId);

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
     */
    void charge(@NonNull Context ctx, @NonNull Validation validation, @NonNull Fees fees);
}
