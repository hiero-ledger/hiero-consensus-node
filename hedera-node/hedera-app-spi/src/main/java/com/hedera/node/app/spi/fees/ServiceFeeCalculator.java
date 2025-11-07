// SPDX-License-Identifier: Apache-2.0
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

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;

import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

/** Calculates transaction and query fees. Null context = approximate, non-null = exact using state. */
public interface ServiceFeeCalculator {

    void accumulateServiceFee(
            @NonNull TransactionBody txnBody,
            @Nullable CalculatorState calculatorState,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule);

    TransactionBody.DataOneOfType getTransactionType();

    default void addExtraFee(
            @NonNull final FeeResult result,
            @NonNull final String feeType,
            @NonNull final Extra extra,
            @NonNull FeeSchedule feeSchedule,
            final long amount) {
        result.addServiceFee(feeType, amount, lookupExtraFee(feeSchedule, extra).fee());
    }
}
