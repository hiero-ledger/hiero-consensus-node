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

package com.hedera.node.app.service.token.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.OptionalInt;

/** Adapts {@link FeeContext} to {@link SimpleFeeCalculator.TxContext}. */
public class SimpleTxContextImpl implements SimpleFeeCalculator.TxContext {
    private final FeeContext feeContext;
    private final ReadableAccountStore accountStore;
    private final ReadableTokenStore tokenStore;
    private final ReadableTokenRelationStore tokenRelStore;

    private SimpleTxContextImpl(@NonNull final FeeContext feeContext) {
        this.feeContext = requireNonNull(feeContext, "feeContext");
        this.accountStore = feeContext.readableStore(ReadableAccountStore.class);
        this.tokenStore = feeContext.readableStore(ReadableTokenStore.class);
        this.tokenRelStore = feeContext.readableStore(ReadableTokenRelationStore.class);
    }

    @NonNull
    public static SimpleFeeCalculator.TxContext from(@NonNull final FeeContext feeContext) {
        return new SimpleTxContextImpl(feeContext);
    }

    @Override
    public int cryptoVerificationsRequired() {
        // Delegates to FeeContext, which provides txInfo.signatureMap().sigPair().size()
        // This maintains proper layering - SimpleTxContextImpl works through the FeeContext
        // abstraction rather than directly accessing TransactionInfo
        return feeContext.numTxnSignatures();
    }

    @Override
    @NonNull
    public Optional<Account> getAccount(@NonNull final AccountID accountId) {
        requireNonNull(accountId, "accountId");
        return Optional.ofNullable(accountStore.getAliasedAccountById(accountId));
    }

    @Override
    public boolean tokenHasCustomFees(@NonNull final TokenID tokenId) {
        requireNonNull(tokenId, "tokenId");
        final var token = tokenStore.get(tokenId);
        return token != null && !token.customFees().isEmpty();
    }

    @Override
    @NonNull
    public OptionalInt customFeeCount(@NonNull final TokenID tokenId) {
        requireNonNull(tokenId, "tokenId");
        final var token = tokenStore.get(tokenId);
        if (token == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(token.customFees().size());
    }

    @Override
    @NonNull
    public Optional<Token> getToken(@NonNull final TokenID tokenId) {
        requireNonNull(tokenId, "tokenId");
        return Optional.ofNullable(tokenStore.get(tokenId));
    }

    @Override
    public boolean existsTokenRelation(@NonNull final AccountID accountId, @NonNull final TokenID tokenId) {
        requireNonNull(accountId, "accountId");
        requireNonNull(tokenId, "tokenId");
        final var account = accountStore.getAliasedAccountById(accountId);
        if (account == null) {
            return false;
        }
        return tokenRelStore.get(account.accountIdOrThrow(), tokenId) != null;
    }

    @Override
    @NonNull
    public Configuration configuration() {
        return feeContext.configuration();
    }

    @Override
    public int numTxnSignatures() {
        return feeContext.numTxnSignatures();
    }

    @Override
    @NonNull
    public FeeCalculatorFactory feeCalculatorFactory() {
        return feeContext.feeCalculatorFactory();
    }
}
