// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.TokenValidations.REQUIRE_NOT_PAUSED;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * Provides validation for custom fees. This class is used by the {@link com.hedera.node.app.service.token.TokenService}
 * to validate custom fees for token creation and fee schedule updates.
 */
public class CustomFeesValidator {
    /**
     * Sentinel token id used to denote that the fee is denominated in the same token as the token being created.
     */
    public static final TokenID SENTINEL_TOKEN_ID =
            TokenID.newBuilder().shardNum(0L).realmNum(0L).tokenNum(0L).build();

    /**
     * Constructs a {@link CustomFeesValidator} instance.
     */
    @Inject
    public CustomFeesValidator() {
        // Needed for Dagger injection
    }

    /**
     * Validates custom fees for {@code TokenCreate} operation.This returns list of custom
     * fees that need to be auto associated with the collector account. This is required
     * for fixed fees with denominating token id set to sentinel value of 0.0.0.
     * NOTE: This logic is subject to change in future PR for TokenCreate
     *
     * @param createdToken       The token being created.
     * @param accountStore       The account store.
     * @param tokenRelationStore The token relation store.
     * @param tokenStore         The token store.
     * @param customFees         The custom fees to validate.
     * @param expiryValidator    The expiry validator to use (for fee collector accounts)
     * @return The set of custom fees that need to auto associate collector accounts
     */
    public List<CustomFee> validateForCreation(
            @NonNull final Token createdToken,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final List<CustomFee> customFees,
            @NonNull final ExpiryValidator expiryValidator) {
        requireNonNull(createdToken);
        requireNonNull(accountStore);
        requireNonNull(tokenRelationStore);
        requireNonNull(tokenStore);
        requireNonNull(customFees);
        requireNonNull(expiryValidator);

        // It is possible that denominating tokenId is set to sentinel value of 0.0.0.
        // In that scenario, the created token should be used as the denominating token.
        // This is a valid scenario for fungible common tokens.
        // For these custom fees we need to associate the collector with the token.
        final List<CustomFee> fees = new ArrayList<>();
        final var tokenType = createdToken.tokenType();
        final var createdTokenId = createdToken.tokenId();
        for (final var fee : customFees) {
            // Validate the fee collector account is in a usable state
            getIfUsable(
                    fee.feeCollectorAccountIdOrElse(AccountID.DEFAULT),
                    accountStore,
                    expiryValidator,
                    INVALID_CUSTOM_FEE_COLLECTOR,
                    INVALID_CUSTOM_FEE_COLLECTOR,
                    TokenHandlerHelper.AccountIDType.NOT_ALIASED_ID);

            final var isSpecified = fee.hasFixedFee() || fee.hasFractionalFee() || fee.hasRoyaltyFee();
            validateTrue(isSpecified, CUSTOM_FEE_NOT_FULLY_SPECIFIED);

            switch (fee.fee().kind()) {
                case FIXED_FEE -> validateFixedFeeForCreation(
                        tokenType, fee, createdTokenId, tokenRelationStore, tokenStore, fees);
                case FRACTIONAL_FEE -> validateFractionalFeeForCreation(tokenType, fee, fees);
                case ROYALTY_FEE -> validateRoyaltyFee(tokenType, fee, tokenRelationStore, tokenStore);
                default -> throw new IllegalArgumentException(
                        "Unexpected value for custom fee type: " + fee.fee().kind());
            }
        }
        return fees;
    }

    /**
     * Validates custom fees for {@code TokenFeeScheduleUpdate} operation.
     * This method validates the following:
     * 1. Fixed fee with denominating token id set to sentinel value of 0.0.0 is not allowed.
     * 2. Fractional fee can be only applied to fungible common tokens.
     * 3. Royalty fee can be only applied to non-fungible unique tokens.
     * 4. The token must be associated to the fee collector account and fee collector account must exist.
     * 5. The token denomination must be a valid token
     *
     * @param token  The token being updated.
     * @param accountStore The account store.
     * @param tokenRelationStore The token relation store.
     * @param tokenStore The token store.
     * @param customFees The custom fees to validate.
     */
    public void validateForFeeScheduleUpdate(
            @NonNull final Token token,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final List<CustomFee> customFees,
            @NonNull final ExpiryValidator expiryValidator) {
        requireNonNull(token);
        requireNonNull(accountStore);
        requireNonNull(tokenRelationStore);
        requireNonNull(tokenStore);
        requireNonNull(customFees);

        final var tokenType = token.tokenType();
        for (final var fee : customFees) {
            final var collectorId = fee.feeCollectorAccountIdOrElse(AccountID.DEFAULT);
            // check if collector is usable
            final var collector = getIfUsable(
                    collectorId,
                    accountStore,
                    expiryValidator,
                    INVALID_CUSTOM_FEE_COLLECTOR,
                    INVALID_CUSTOM_FEE_COLLECTOR,
                    TokenHandlerHelper.AccountIDType.NOT_ALIASED_ID);
            validateTrue(collector != null, INVALID_CUSTOM_FEE_COLLECTOR);

            switch (fee.fee().kind()) {
                case FIXED_FEE -> {
                    final var fixedFee = fee.fixedFeeOrThrow();
                    // validate any explicit token denomination set
                    final var fixedFeeAmount = fee.fixedFee().amount();
                    validateTrue(fixedFeeAmount > 0, CUSTOM_FEE_MUST_BE_POSITIVE);
                    if (fixedFee.hasDenominatingTokenId()) {
                        validateExplicitTokenDenomination(
                                collectorId, fixedFee.denominatingTokenId(), tokenRelationStore, tokenStore);
                    }
                }
                case FRACTIONAL_FEE -> // fractional fee can be only applied to fungible common tokens
                validateFractionalFeeForFeeScheduleUpdate(token, tokenRelationStore, collectorId, fee);
                case ROYALTY_FEE -> // royalty fee can be only applied to non-fungible unique tokens
                validateRoyaltyFee(tokenType, fee, tokenRelationStore, tokenStore);
                default -> throw new HandleException(CUSTOM_FEE_NOT_FULLY_SPECIFIED);
            }
        }
    }

    /**
     * Validate explicitly set token denomination for custom fees.
     * @param feeCollectorNum The fee collector account number.
     * @param tokenNum The token number used for token denomination.
     * @param tokenRelationStore The token relation store.
     * @param tokenStore The token store.
     */
    private void validateExplicitTokenDenomination(
            @NonNull final AccountID feeCollectorNum,
            @NonNull final TokenID tokenNum,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final WritableTokenStore tokenStore) {
        final var denomToken = getIfUsable(tokenNum, tokenStore, REQUIRE_NOT_PAUSED, INVALID_TOKEN_ID_IN_CUSTOM_FEES);
        validateTrue(isFungibleCommon(denomToken.tokenType()), CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON);
        validateTrue(tokenRelationStore.get(feeCollectorNum, tokenNum) != null, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);
    }

    /**
     * Validates that the given token type is fungible common.
     * @param tokenType The token type to validate.
     * @return {@code true} if the token type is fungible common, otherwise {@code false}
     */
    private boolean isFungibleCommon(@NonNull final TokenType tokenType) {
        return tokenType.equals(TokenType.FUNGIBLE_COMMON);
    }

    /**
     * Validates that the given token type is non-fungible unique.
     * @param tokenType The token type to validate.
     * @return {@code true} if the token type is non-fungible unique, otherwise {@code false}
     */
    private boolean isNonFungibleUnique(@NonNull final TokenType tokenType) {
        return tokenType.equals(TokenType.NON_FUNGIBLE_UNIQUE);
    }

    private void validateFixedFeeForCreation(
            @NonNull final TokenType tokenType,
            @NonNull final CustomFee fee,
            @NonNull final TokenID createdTokenId,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final List<CustomFee> feesWithCollectorsToAutoAssociate) {
        final var fixedFee = fee.fixedFeeOrThrow();

        validateTrue(fixedFee.amount() > 0, CUSTOM_FEE_MUST_BE_POSITIVE);

        if (fixedFee.hasDenominatingTokenId()) {
            // If the denominating token id is set to sentinel value 0.0.0, then the fee is
            // denominated in the same token as the token being created.
            // For these fees the collector should be auto-associated to the token.
            if (fixedFee.denominatingTokenIdOrThrow().equals(SENTINEL_TOKEN_ID)) {
                validateTrue(isFungibleCommon(tokenType), CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON);
                final var copy = fee.copyBuilder();
                final var fixedFeeCopy = fixedFee.copyBuilder();
                copy.fixedFee(fixedFeeCopy.denominatingTokenId(createdTokenId).build());
                feesWithCollectorsToAutoAssociate.add(copy.build());
            } else {
                validateExplicitTokenDenomination(
                        fee.feeCollectorAccountId(), fixedFee.denominatingTokenId(), tokenRelationStore, tokenStore);
            }
        }
    }

    private void validateFractionalFeeForCreation(TokenType tokenType, CustomFee fee, List<CustomFee> fees) {
        validateTrue(isFungibleCommon(tokenType), CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON);
        validateFractionalFee(fee);
        fees.add(fee);
    }

    private void validateRoyaltyFee(
            @NonNull final TokenType tokenType,
            @NonNull final CustomFee fee,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final WritableTokenStore tokenStore) {
        validateTrue(isNonFungibleUnique(tokenType), CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE);
        final var royaltyFee = fee.royaltyFeeOrThrow();

        final var numerator = royaltyFee.exchangeValueFraction().numerator();
        final var denominator = royaltyFee.exchangeValueFraction().denominator();
        validateTrue(denominator != 0, FRACTION_DIVIDES_BY_ZERO);
        validateTrue(numerator > 0 && denominator > 0, CUSTOM_FEE_MUST_BE_POSITIVE);
        validateTrue(numerator <= denominator, ROYALTY_FRACTION_CANNOT_EXCEED_ONE);

        if (royaltyFee.hasFallbackFee()) {
            validateTrue(royaltyFee.fallbackFee().amount() > 0, CUSTOM_FEE_MUST_BE_POSITIVE);
            final var fallbackFee = royaltyFee.fallbackFeeOrThrow();
            if (fallbackFee.hasDenominatingTokenId()) {
                final var denominatingTokenId = fallbackFee.denominatingTokenIdOrThrow();
                validateTrue(denominatingTokenId.tokenNum() != 0, CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON);
                validateExplicitTokenDenomination(
                        fee.feeCollectorAccountId(), denominatingTokenId, tokenRelationStore, tokenStore);
            }
        }
    }

    private void validateFractionalFeeForFeeScheduleUpdate(
            @NonNull final Token token,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final AccountID collectorId,
            @NonNull final CustomFee fee) {
        final var tokenType = token.tokenType();
        // fractional fee can be only applied to fungible common tokens
        validateTrue(isFungibleCommon(tokenType), CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON);
        final var tokenId = token.tokenId();
        final var relation = tokenRelationStore.get(collectorId, tokenId);
        validateTrue(relation != null, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);
        validateFractionalFee(fee);
    }

    private void validateFractionalFee(@NonNull CustomFee fee) {
        final var fractionalFee = fee.fractionalFee();
        final var numerator = fractionalFee.fractionalAmount().numerator();
        final var denominator = fractionalFee.fractionalAmount().denominator();
        final var minimumUnitsToCollect = fractionalFee.minimumAmount();
        final var nominalMax = fractionalFee.maximumAmount();
        final var maximumUnitsToCollect = nominalMax == 0 ? Long.MAX_VALUE : nominalMax;

        validateTrue(denominator != 0, FRACTION_DIVIDES_BY_ZERO);
        validateTrue(numerator > 0 && denominator > 0, CUSTOM_FEE_MUST_BE_POSITIVE);
        validateTrue(minimumUnitsToCollect >= 0 && maximumUnitsToCollect >= 0, CUSTOM_FEE_MUST_BE_POSITIVE);
        validateTrue(maximumUnitsToCollect >= minimumUnitsToCollect, FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT);
    }
}
