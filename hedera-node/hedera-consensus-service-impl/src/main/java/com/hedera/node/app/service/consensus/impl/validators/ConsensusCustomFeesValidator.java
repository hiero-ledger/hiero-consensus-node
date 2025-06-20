// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.TokenValidations.REQUIRE_NOT_PAUSED;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsableForAliasedId;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;

public class ConsensusCustomFeesValidator {
    /**
     * Constructs a {@link ConsensusCustomFeesValidator} instance.
     */
    @Inject
    public ConsensusCustomFeesValidator() {
        // Needed for Dagger injection
    }

    /**
     * Validates custom fees for {@code ConsensusCreateTopic} operation.
     *
     * @param accountStore       The account store.
     * @param tokenRelationStore The token relation store.
     * @param tokenStore         The token store.
     * @param customFees         The custom fees to validate.
     * @param expiryValidator    The expiry validator to use (for fee collector accounts)
     */
    public void validate(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final List<FixedCustomFee> customFees,
            @NonNull final ExpiryValidator expiryValidator) {
        requireNonNull(accountStore);
        requireNonNull(tokenRelationStore);
        requireNonNull(tokenStore);
        requireNonNull(customFees);
        requireNonNull(expiryValidator);

        for (final var fee : customFees) {
            // Validate the fee collector account is in a usable state
            getIfUsableForAliasedId(
                    fee.feeCollectorAccountIdOrElse(AccountID.DEFAULT),
                    accountStore,
                    expiryValidator,
                    INVALID_CUSTOM_FEE_COLLECTOR);

            final var isSpecified = fee.hasFixedFee();
            validateTrue(isSpecified, CUSTOM_FEE_NOT_FULLY_SPECIFIED);
            validateFixedFee(fee, tokenRelationStore, tokenStore);
        }
    }

    private void validateFixedFee(
            @NonNull final FixedCustomFee fee,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final ReadableTokenStore tokenStore) {
        final var fixedFee = fee.fixedFeeOrThrow();
        validateTrue(fixedFee.amount() > 0, CUSTOM_FEE_MUST_BE_POSITIVE);
        if (fixedFee.hasDenominatingTokenId()) {
            validateExplicitTokenDenomination(
                    fee.feeCollectorAccountId(),
                    fixedFee.denominatingTokenId(),
                    fixedFee.amount(),
                    tokenRelationStore,
                    tokenStore);
        }
    }

    /**
     * Validate explicitly set token denomination for custom fees.
     *
     * @param feeCollectorNum The fee collector account number.
     * @param tokenNum The token number used for token denomination.
     * @param feeAmount The fee amount.
     * @param tokenRelationStore The token relation store.
     * @param tokenStore The token store.
     */
    private void validateExplicitTokenDenomination(
            @NonNull final AccountID feeCollectorNum,
            @NonNull final TokenID tokenNum,
            final long feeAmount,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final ReadableTokenStore tokenStore) {
        final var denomToken = getIfUsable(tokenNum, tokenStore, REQUIRE_NOT_PAUSED, INVALID_TOKEN_ID_IN_CUSTOM_FEES);
        if (denomToken.supplyType().equals(TokenSupplyType.FINITE)) {
            validateFalse(feeAmount > denomToken.maxSupply(), AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY);
        }
        validateTrue(isFungibleCommon(denomToken.tokenType()), CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON);
        final var tokenRel = tokenRelationStore.get(feeCollectorNum, tokenNum);
        validateTrue(tokenRel != null, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);
        validateFalse(tokenRel.frozen(), ACCOUNT_FROZEN_FOR_TOKEN);
    }

    /**
     * Validates that the given token type is fungible common.
     *
     * @param tokenType The token type to validate.
     * @return {@code true} if the token type is fungible common, otherwise {@code false}
     */
    private boolean isFungibleCommon(@NonNull final TokenType tokenType) {
        return tokenType.equals(TokenType.FUNGIBLE_COMMON);
    }
}
