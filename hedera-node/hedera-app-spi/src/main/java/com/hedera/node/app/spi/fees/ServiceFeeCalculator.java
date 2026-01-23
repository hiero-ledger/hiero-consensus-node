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
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates transaction and query fees. Null context = approximate, non-null = exact using state. */
public interface ServiceFeeCalculator {
    /**
     * Accumulated service fees as a side effect into the given fee result. This will be implemented by every
     * single handler's fee calculator.
     *
     * @param txnBody the transaction body
     * @param simpleFeeContext the fee context
     * @param feeResult the fee result
     * @param feeSchedule the fee schedule
     */
    void accumulateServiceFee(
            @NonNull TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule);
    /**
     * Returns the transaction type this calculator is for.
     * @return the transaction type
     */
    TransactionBody.DataOneOfType getTransactionType();

    /**
     * Adds an extra fee to the result.
     *
     * @param result the fee result
     * @param serviceFeeDef the service fee definition containing operation-specific extras with includedCount
     * @param extra the extra fee
     * @param feeSchedule the fee schedule
     * @param amount the amount of the extra fee
     */
    default void addExtraFee(
            @NonNull final FeeResult result,
            @NonNull final ServiceFeeDefinition serviceFeeDef,
            @NonNull final Extra extra,
            @NonNull FeeSchedule feeSchedule,
            final long amount) {
        for (ExtraFeeReference ref : serviceFeeDef.extras()) {
            if (ref.name() == extra) {
                int included = ref.includedCount();
                long extraFee = lookupExtraFee(feeSchedule, ref.name()).fee();
                result.addServiceExtraFeeTinycents(ref.name().name(), extraFee, amount, included);
            }
        }
    }
}
