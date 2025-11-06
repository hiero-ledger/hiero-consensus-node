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

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.Query;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.OptionalInt;
import org.hiero.hapi.fees.FeeResult;

/** Calculates transaction and query fees. Null context = approximate, non-null = exact using state. */
public interface SimpleFeeCalculator {

    /** Provides state access for exact fee calculation. */
    interface TxContext {
        /**
         * Returns the number of cryptographic signature verifications required for this transaction.
         * Used to calculate fees based on signature complexity.
         *
         * <p>This typically corresponds to the number of signature pairs in the transaction's
         * signature map ({@code txInfo.signatureMap().sigPair().size()}), provided by the
         * underlying {@link FeeContext}.
         *
         * @return the number of signature verifications required
         */
        int cryptoVerificationsRequired();

        /**
         * Retrieves the account with the given ID from state.
         *
         * @param accountId the account ID to retrieve
         * @return an Optional containing the account if found, empty otherwise
         */
        @NonNull
        Optional<Account> getAccount(@NonNull AccountID accountId);

        /**
         * Checks if a token has custom fees defined.
         *
         * @param tokenId the token ID to check
         * @return true if the token has custom fees, false otherwise
         */
        boolean tokenHasCustomFees(@NonNull TokenID tokenId);

        /**
         * Returns the number of custom fees defined for a token.
         *
         * @param tokenId the token ID to check
         * @return an OptionalInt containing the count if the token exists, empty otherwise
         */
        @NonNull
        OptionalInt customFeeCount(@NonNull TokenID tokenId);

        /**
         * Retrieves the token with the given ID from state.
         *
         * @param tokenId the token ID to retrieve
         * @return an Optional containing the token if found, empty otherwise
         */
        @NonNull
        Optional<Token> getToken(@NonNull TokenID tokenId);

        /**
         * Checks if a token association exists between an account and a token.
         *
         * @param accountId the account ID
         * @param tokenId the token ID
         * @return true if the account has an association with the token, false otherwise
         */
        boolean existsTokenRelation(@NonNull AccountID accountId, @NonNull TokenID tokenId);

        /**
         * Returns the network configuration.
         *
         * @return the configuration object
         */
        @NonNull
        Configuration configuration();

        /**
         * Returns the number of signatures attached to this transaction.
         * Used to calculate SIGNATURES extra fees per HIP-1261.
         *
         * @return the number of transaction signatures
         */
        int numTxnSignatures();

        /**
         * Returns the fee calculator factory for accessing fee schedules.
         *
         * @return the fee calculator factory
         */
        @NonNull
        FeeCalculatorFactory feeCalculatorFactory();
    }

    /** Provides state access for exact query fee calculation. */
    interface QueryContext {
        int cryptoVerificationsRequired();
    }

    @NonNull
    FeeResult calculateTxFee(@NonNull FeeContext feeContext);

    @NonNull
    FeeResult calculateQueryFee(@NonNull Query query, @Nullable QueryContext context);
}
