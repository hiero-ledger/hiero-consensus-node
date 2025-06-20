// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.handlers.customfee;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.TokenValidations.REQUIRE_NOT_PAUSED;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.node.app.service.token.ReadableTokenStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ConsensusCustomFeeAssessor {

    /**
     * Constructs a {@link ConsensusCustomFeeAssessor} instance.
     */
    @Inject
    public ConsensusCustomFeeAssessor() {
        // Needed for Dagger injection
    }

    /**
     * Constructs a map of synthetic crypto transfer transaction bodies.
     * Each entry in the map represents a custom fee payment, with one transaction body per custom fee.
     *
     * @param customFees List of custom fees to be charged
     * @param payer The payer Account ID
     * @return A map where each key is a FixedCustomFee and each value is a corresponding CryptoTransferTransactionBody.
     */
    public Map<FixedCustomFee, CryptoTransferTransactionBody> assessCustomFee(
            @NonNull final List<FixedCustomFee> customFees, @NonNull final AccountID payer) {
        final Map<FixedCustomFee, CryptoTransferTransactionBody> transactionBodies = new HashMap<>();

        // build crypto transfer bodies for the first layer of custom fees,
        // if there is a second or third layer it will be assessed in crypto transfer handler
        for (FixedCustomFee fee : customFees) {
            final var tokenTransfers = new ArrayList<TokenTransferList>();
            TransferList.Builder hbarTransfers = TransferList.newBuilder();

            final var fixedFee = fee.fixedFeeOrThrow();
            if (fixedFee.hasDenominatingTokenId()) {
                tokenTransfers.add(buildCustomFeeTokenTransferList(payer, fee.feeCollectorAccountId(), fixedFee));
            } else {
                final var accountAmounts = buildCustomFeeHbarTransferList(payer, fee.feeCollectorAccountId(), fixedFee);
                hbarTransfers.accountAmounts(accountAmounts.toArray(AccountAmount[]::new));
            }

            // build the synthetic body
            final var syntheticBodyBuilder = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers.build())
                    .tokenTransfers(tokenTransfers);

            transactionBodies.put(fee, syntheticBodyBuilder.build());
        }

        return transactionBodies;
    }

    private List<AccountAmount> buildCustomFeeHbarTransferList(AccountID payer, AccountID collector, FixedFee fee) {
        return List.of(
                AccountAmount.newBuilder()
                        .accountID(payer)
                        .amount(-fee.amount())
                        .build(),
                AccountAmount.newBuilder()
                        .accountID(collector)
                        .amount(fee.amount())
                        .build());
    }

    private TokenTransferList buildCustomFeeTokenTransferList(AccountID payer, AccountID collector, FixedFee fee) {
        return TokenTransferList.newBuilder()
                .token(fee.denominatingTokenId())
                .transfers(
                        AccountAmount.newBuilder()
                                .accountID(payer)
                                .amount(-fee.amount())
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(collector)
                                .amount(fee.amount())
                                .build())
                .build();
    }

    @VisibleForTesting
    public AccountID getTokenTreasury(TokenID tokenId, ReadableTokenStore tokenStore) {
        final var token = getIfUsable(tokenId, tokenStore, REQUIRE_NOT_PAUSED, INVALID_TOKEN_ID_IN_CUSTOM_FEES);
        return token.treasuryAccountIdOrThrow();
    }
}
