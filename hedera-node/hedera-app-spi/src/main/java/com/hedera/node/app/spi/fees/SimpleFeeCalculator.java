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
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;

/** Calculates transaction and query fees. Null context = approximate, non-null = exact using state. */
public interface SimpleFeeCalculator {

    @NonNull
    FeeResult calculateTxFee(@NonNull TransactionBody txnBody, @NonNull SimpleFeeContext context);

    @NonNull
    FeeResult calculateQueryFee(@NonNull Query query, @NonNull SimpleFeeContext context);

    long getExtraFee(Extra extra);

    interface SimpleFeeContext {
        int numTxnSignatures(); // number of signatures in the transaction

        int numTxnBytes(); // added in a different PR so we can have BYTE extras in the node fees

        FeeContext feeContext(); // may be null

        QueryContext queryContext(); // may be null

        ServiceFeeCalculator.EstimationMode estimationMode(); // Intrinsic or Stateful
    }
}
