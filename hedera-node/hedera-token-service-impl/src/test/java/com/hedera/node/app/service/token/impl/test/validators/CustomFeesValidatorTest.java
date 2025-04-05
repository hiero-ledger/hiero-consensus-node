// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatNoException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomFeesValidatorTest extends CryptoTokenHandlerTestBase {
    private final CustomFeesValidator subject = new CustomFeesValidator();

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ExpiryValidator expiryValidator;

    @BeforeEach
    public void commonSetUp() {
        super.setUp();
        refreshWritableStores();

        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(ResponseCodeEnum.OK);
    }

    @Test
    @DisplayName("throws if fee collector is not set on fee schedule update")
    void validateNullFeeCollectorOnFeeScheduleUpdate() {
        final var nullCollectorFee = setFeeCollector(customFees, null);
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        nullCollectorFee,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("throws if fee collector doesn't exist on fee schedule update")
    void validateMissingFeeCollectorOnFeeScheduleUpdate() {
        final var missingFeeCollectorFee = setFeeCollector(
                customFees, AccountID.newBuilder().accountNum(100).build());
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        missingFeeCollectorFee,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("throws if fee collector is default instance on fee schedule update")
    void validateDefaultInstanceOnFeeScheduleUpdate() {
        final var missingFeeCollectorFee =
                setFeeCollector(customFees, AccountID.newBuilder().build());
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        missingFeeCollectorFee,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("fixed fee and fractional fee for fungible tokens are allowed on fee schedule update")
    void validateFixedFeeAndFractionalFeesOnFeeScheduleUpdate() {
        assertThatNoException()
                .isThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        customFees,
                        expiryValidator));
    }

    @Test
    @DisplayName("royalty fee can be set only for non fungible unique tokens on fee schedule update")
    void royaltyFeeForFungibleTokenFailOnFeeScheduleUpdates() {
        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        feeWithRoyalty.add(withRoyaltyFee(royaltyFee));
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE));
    }

    @Test
    @DisplayName("royalty fee can be set for non fungible unique tokens on fee schedule update")
    void royaltyFeeForNonFungibleTokenSucceedsOnFeeScheduleUpdate() {
        refreshWritableStores();
        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        feeWithRoyalty.add(withRoyaltyFee(royaltyFee));
        assertThatNoException()
                .isThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator));
    }

    @Test
    @DisplayName("royalty fee for NFTs will fail if the denominating token is missing on fee schedule update")
    void royaltyFeeFailsWithMissingTokenOnFeeScheduleUpdate() {
        writableTokenState = emptyWritableTokenState();
        given(writableStates.<TokenID, Token>get(TOKENS)).willReturn(writableTokenState);
        writableTokenStore = new WritableTokenStore(writableStates, writableEntityCounters);

        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        feeWithRoyalty.add(
                withRoyaltyFee(royaltyFee.copyBuilder().fallbackFee(htsFixedFee).build()));
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_ID_IN_CUSTOM_FEES));
    }

    @Test
    @DisplayName("royalty fee for NFTs will fail if the denominating token is missing on fee schedule update")
    void royaltyFeeFailsFungibleDenomOnFeeScheduleUpdate() {
        refreshWritableStores();
        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        final var nftDenom = royaltyFee
                .copyBuilder()
                .fallbackFee(hbarFixedFee
                        .copyBuilder()
                        .denominatingTokenId(TokenID.newBuilder()
                                .tokenNum(nonFungibleTokenId.tokenNum())
                                .build()))
                .build();
        feeWithRoyalty.add(withRoyaltyFee(nftDenom));
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON));
    }

    @Test
    void missingTokenAssociationForRoyaltyFeeFailsOnFeeScheduleUpdate() {
        refreshWritableStores();
        readableTokenRelState = emptyReadableTokenRelsStateBuilder().build();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withRoyaltyFee(royaltyFee
                                .copyBuilder()
                                .fallbackFee(htsFixedFee)
                                .build())),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("fractional fee can be set only for fungible unique tokens on fee schedule update")
    void fractionalFeeForNonFungibleTokenFailsOnFeeScheduleUpdate() {
        final List<CustomFee> feeWithFractional = new ArrayList<>();
        feeWithFractional.add(withFractionalFee(fractionalFee));
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithFractional,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON));
    }

    @Test
    @DisplayName("fixed fee can be set for non fungible unique tokens on fee schedule update")
    void fixedFeeIsAllowedForNonFungibleTokenOnFeeScheduleUpdate() {
        refreshWritableStores();
        final List<CustomFee> feeWithFixed = new ArrayList<>();
        feeWithFixed.add(withFixedFee(hbarFixedFee));
        assertThatNoException()
                .isThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithFixed,
                        expiryValidator));
    }

    @Test
    @DisplayName(
            "fails if there is no token relation between token and fee collector in fixed fee on fee schedule update")
    void failsIfTokenRelationIsMissingInFixedFeeOnFeeScheduleUpdate() {
        readableTokenRelState = emptyReadableTokenRelsStateBuilder().build();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFixedFee(hbarFixedFee), withFractionalFee(fractionalFee)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR));
    }

    @Test
    @DisplayName(
            "fails if there is no token relation between token and fee collector in fractional fee on fee schedule update")
    void failsIfTokenRelationIsMissingForFractionalFeeOnFeeScheduleUpdate() {
        readableTokenRelState = emptyReadableTokenRelsStateBuilder().build();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFractionalFee(fractionalFee)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("token denomination should be fungible common for fixed fee on fee schedule update")
    void validateTokenDenominationForFixedFeeOnFeeScheduleUpdate() {
        refreshWritableStores();
        final var newFee = hbarFixedFee
                .copyBuilder()
                .denominatingTokenId(TokenID.newBuilder()
                        .tokenNum(nonFungibleTokenId.tokenNum())
                        .build())
                .build();
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFixedFee(newFee)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON));
    }

    @Test
    void nullParamsThrowOnFeeScheduleUpdate() {
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        null,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        customFees,
                        expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken, null, readableTokenRelStore, writableTokenStore, customFees, expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken, readableAccountStore, null, writableTokenStore, customFees, expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken, readableAccountStore, readableTokenRelStore, null, customFees, expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        null,
                        expiryValidator))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void failsIfFractionalFeeDenominatorIsSetToZero() {
        final var fractionalFeeWithZeroDenominator = FractionalFee.newBuilder()
                .maximumAmount(50L)
                .minimumAmount(5L)
                .fractionalAmount(
                        Fraction.newBuilder().numerator(1).denominator(0).build())
                .netOfTransfers(false)
                .build();

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFractionalFee(fractionalFeeWithZeroDenominator)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("FRACTION_DIVIDES_BY_ZERO");
    }

    @Test
    void failsIfFractionalFeeNumeratorIsNegative() {
        final var fractionalFeeWithNegativeNumerator = FractionalFee.newBuilder()
                .maximumAmount(50L)
                .minimumAmount(5L)
                .fractionalAmount(
                        Fraction.newBuilder().numerator(-1).denominator(10).build())
                .netOfTransfers(false)
                .build();

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFractionalFee(fractionalFeeWithNegativeNumerator)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("CUSTOM_FEE_MUST_BE_POSITIVE");
    }

    @Test
    void failsIfFractionalFeeMinimumAmountIsNegative() {
        final var fractionalFeeWithNegativeMinimumAmount = FractionalFee.newBuilder()
                .maximumAmount(50L)
                .minimumAmount(-5L)
                .fractionalAmount(
                        Fraction.newBuilder().numerator(1).denominator(10).build())
                .netOfTransfers(false)
                .build();

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFractionalFee(fractionalFeeWithNegativeMinimumAmount)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("CUSTOM_FEE_MUST_BE_POSITIVE");
    }

    @Test
    void failsIfFractionalFeeMaximumAmountIsNegative() {
        final var fractionalFeeWithNegativeMaximumAmount = FractionalFee.newBuilder()
                .maximumAmount(-50L)
                .minimumAmount(5L)
                .fractionalAmount(
                        Fraction.newBuilder().numerator(1).denominator(10).build())
                .netOfTransfers(false)
                .build();

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFractionalFee(fractionalFeeWithNegativeMaximumAmount)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("CUSTOM_FEE_MUST_BE_POSITIVE");
    }

    @Test
    void failsIfHtsFixedFeeAmountIsNegative() {
        final var htsFixedFeeWithNegativeAmount = FixedFee.newBuilder()
                .amount(-10L)
                .denominatingTokenId(fungibleTokenId)
                .build();

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFixedFee(htsFixedFeeWithNegativeAmount)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("CUSTOM_FEE_MUST_BE_POSITIVE");
    }

    @Test
    void failsIfFractionalFeeMaximumAmountIsLessThanMinimumAmount() {
        final var fractionalFeeWithWrongMinMaxAmount = FractionalFee.newBuilder()
                .maximumAmount(5L)
                .minimumAmount(50L)
                .fractionalAmount(
                        Fraction.newBuilder().numerator(1).denominator(10).build())
                .netOfTransfers(false)
                .build();

        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFractionalFee(fractionalFeeWithWrongMinMaxAmount)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT");
    }

    @Test
    void failsIfEmptyCustomFeesOnFeeScheduleUpdate() {
        assertThatThrownBy(() -> subject.validateForFeeScheduleUpdate(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(CustomFee.newBuilder()
                                .feeCollectorAccountId(idFactory.newAccountId(accountNum.longValue()))
                                .build()),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .hasMessage("CUSTOM_FEE_NOT_FULLY_SPECIFIED");
    }

    @Test
    @DisplayName("Custom fee validation for TokenCreate")
    void validateCustomFeeForCreation() {
        final var requireAutoAssociation = subject.validateForCreation(
                fungibleToken,
                readableAccountStore,
                readableTokenRelStore,
                writableTokenStore,
                customFees,
                expiryValidator);
        assertThat(requireAutoAssociation).contains(customFractionalFee);
    }

    @Test
    @DisplayName("Custom fee validation for TokenCreate with self denominating tokenId")
    void validateCustomFeeForCreationWithSelfDenomination() {
        final var fixesFeeWithSelfDenomination = hbarFixedFee
                .copyBuilder()
                .denominatingTokenId(TokenID.newBuilder().tokenNum(0).build())
                .build();
        final var expectedFeeWithNewToken =
                hbarFixedFee.copyBuilder().denominatingTokenId(fungibleTokenId).build();
        final var fees = List.of(withFixedFee(fixesFeeWithSelfDenomination), withFractionalFee(fractionalFee));
        final var requireAutoAssociation = subject.validateForCreation(
                fungibleToken, readableAccountStore, readableTokenRelStore, writableTokenStore, fees, expiryValidator);
        assertThat(requireAutoAssociation)
                .hasSize(2)
                .contains(withFixedFee(expectedFeeWithNewToken))
                .contains(withFractionalFee(fractionalFee));
    }

    @Test
    @DisplayName("throws if fee collector is not set on token create")
    void validateNullFeeCollectorOnTokenCreate() {
        final var nullCollectorFee = setFeeCollector(customFees, null);
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        nullCollectorFee,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("throws if fee collector doesn't exist on token create")
    void validateMissingFeeCollectorOnTokenCreate() {
        final var missingFeeCollectorFee = setFeeCollector(
                customFees, AccountID.newBuilder().accountNum(100).build());
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        missingFeeCollectorFee,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("throws if fee collector is default instance")
    void validateDefaultInstanceOnTokenCreate() {
        final var missingFeeCollectorFee =
                setFeeCollector(customFees, AccountID.newBuilder().build());
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        missingFeeCollectorFee,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("fixed fee and fractional fee for fungible tokens are allowed")
    void validateFixedFeeAndFractionalFeesOnTokenCreate() {
        assertThatNoException()
                .isThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        customFees,
                        expiryValidator));
    }

    @Test
    @DisplayName("royalty fee can be set only for non fungible unique tokens")
    void royaltyFeeForFungibleTokenFailOnTokenCreate() {
        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        feeWithRoyalty.add(withRoyaltyFee(royaltyFee));
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE));
    }

    @Test
    @DisplayName("royalty fee can be set for non fungible unique tokens")
    void royaltyFeeForNonFungibleTokenSucceedsOnTokenCreate() {
        refreshWritableStores();
        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        feeWithRoyalty.add(withRoyaltyFee(royaltyFee));
        assertThatNoException()
                .isThrownBy(() -> subject.validateForCreation(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator));
    }

    @Test
    @DisplayName("royalty fee for NFTs will fail if the denominating token is missing")
    void royaltyFeeFailsWithMissingTokenOnTokenCreate() {
        writableTokenState = emptyWritableTokenState();
        given(writableStates.<TokenID, Token>get(TOKENS)).willReturn(writableTokenState);
        writableTokenStore = new WritableTokenStore(writableStates, writableEntityCounters);

        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        feeWithRoyalty.add(
                withRoyaltyFee(royaltyFee.copyBuilder().fallbackFee(htsFixedFee).build()));
        assertThatThrownBy(() -> subject.validateForCreation(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_ID_IN_CUSTOM_FEES));
    }

    @Test
    @DisplayName("royalty fee for NFTs will fail if the denominating token is missing")
    void royaltyFeeFailsFungibleDenomOnTokenCreate() {
        refreshWritableStores();
        final List<CustomFee> feeWithRoyalty = new ArrayList<>();
        final var nftDenom = royaltyFee
                .copyBuilder()
                .fallbackFee(hbarFixedFee
                        .copyBuilder()
                        .denominatingTokenId(nonFungibleTokenId)
                        .build())
                .build();
        feeWithRoyalty.add(withRoyaltyFee(nftDenom));
        assertThatThrownBy(() -> subject.validateForCreation(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithRoyalty,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON));
    }

    @Test
    void missingTokenAssociationForRoyaltyFeeFailsOnTokenCreate() {
        refreshWritableStores();
        readableTokenRelState = emptyReadableTokenRelsStateBuilder().build();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);

        assertThatThrownBy(() -> subject.validateForCreation(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withRoyaltyFee(royaltyFee
                                .copyBuilder()
                                .fallbackFee(htsFixedFee)
                                .build())),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("fractional fee can be set only for fungible unique tokens")
    void fractionalFeeForNonFungibleTokenFailsOnTokenCreate() {
        final List<CustomFee> feeWithFractional = new ArrayList<>();
        feeWithFractional.add(withFractionalFee(fractionalFee));
        assertThatThrownBy(() -> subject.validateForCreation(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithFractional,
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON));
    }

    @Test
    @DisplayName("fixed fee can be set for non fungible unique tokens")
    void fixedFeeIsAllowedForNonFungibleTokenOnTokenCreate() {
        refreshWritableStores();
        final List<CustomFee> feeWithFixed = new ArrayList<>();
        feeWithFixed.add(withFixedFee(hbarFixedFee));
        assertThatNoException()
                .isThrownBy(() -> subject.validateForCreation(
                        nonFungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        feeWithFixed,
                        expiryValidator));
    }

    @Test
    @DisplayName("fails if there is no token relation between token and fee collector in fixed fee")
    void failsIfTokenRelationIsMissingInFixedFeeOnTokenCreate() {
        readableTokenRelState = emptyReadableTokenRelsStateBuilder().build();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);

        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFixedFee(htsFixedFee)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR));
    }

    @Test
    @DisplayName("token denomination should be fungible common for fixed fee")
    void validateTokenDenominationForFixedFeeOnTokenCreate() {
        refreshWritableStores();
        final var newFee = hbarFixedFee
                .copyBuilder()
                .denominatingTokenId(nonFungibleTokenId)
                .build();
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(withFixedFee(newFee)),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON));
    }

    @Test
    void nullParamsThrowOnTokenCreate() {
        assertThatThrownBy(() -> subject.validateForCreation(
                        null,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        customFees,
                        expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken, null, readableTokenRelStore, writableTokenStore, customFees, expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken, readableAccountStore, null, writableTokenStore, customFees, expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken, readableAccountStore, readableTokenRelStore, null, customFees, expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        null,
                        expiryValidator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        customFees,
                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void failsIfEmptyCustomFeesOnTokenCreate() {
        assertThatThrownBy(() -> subject.validateForCreation(
                        fungibleToken,
                        readableAccountStore,
                        readableTokenRelStore,
                        writableTokenStore,
                        List.of(CustomFee.newBuilder()
                                .feeCollectorAccountId(idFactory.newAccountId(accountNum.longValue()))
                                .build()),
                        expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_NOT_FULLY_SPECIFIED));
    }

    private List<CustomFee> setFeeCollector(List<CustomFee> original, AccountID feeCollector) {
        List<CustomFee> copy = new ArrayList<>();
        for (CustomFee fee : original) {
            copy.add(fee.copyBuilder().feeCollectorAccountId(feeCollector).build());
        }
        return copy;
    }
}
