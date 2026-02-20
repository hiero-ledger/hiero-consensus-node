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

import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;

/** Calculates transaction and query fees. Null context = approximate, non-null = exact using state. */
public interface SimpleFeeCalculator {

    /**
     * Calculates transaction fees.
     * @param txnBody the transaction body
     * @param simpleFeeContext the transaction context
     * @return the fee result
     */
    @NonNull
    FeeResult calculateTxFee(@NonNull TransactionBody txnBody, @NonNull SimpleFeeContext simpleFeeContext);

    /**
     * Calculates query fees.
     * @param query the query
     * @param simpleFeeContext the query context
     * @return the fee result
     */
    @NonNull
    FeeResult calculateQueryFee(@NonNull Query query, @NonNull SimpleFeeContext simpleFeeContext);

    /**
     * Returns the extra fee for the given extra.
     * @param extra the extra
     * @return the extra fee
     */
    long getExtraFee(Extra extra);

    /**
     * Returns the high volume multiplier for the given transaction body and fee context.
     * @param txnBody the transaction body
     * @param feeContext the fee context
     * @return the high volume multiplier
     */
    long highVolumeRawMultiplier(@NonNull TransactionBody txnBody, @NonNull FeeContext feeContext);
}
