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
    public void charge(@NonNull final Context ctx, @NonNull final Validation validation, @NonNull final Fees fees) {
        requireNonNull(ctx);
        requireNonNull(validation);
        requireNonNull(fees);
    }

    private record PassedValidation(boolean creatorDidDueDiligence, @Nullable ResponseCodeEnum maybeErrorStatus)
            implements Validation {
        private static final PassedValidation INSTANCE = new PassedValidation(true, null);
    }
}
