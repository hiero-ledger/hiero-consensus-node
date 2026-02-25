// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.updatetokencustomfees;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesTranslator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateTokenCustomFeesDecoderTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    Configuration configuration;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    protected HederaNativeOperations nativeOperations;

    private UpdateTokenCustomFeesDecoder subject;

    private final Address zeroAddress = Address.wrap("0x0000000000000000000000000000000000000000");
    // Input fees
    private final Tuple FIXED_HBAR_FEE_TUPLE = Tuple.of(10L, zeroAddress, true, false, OWNER_HEADLONG_ADDRESS);
    private final Tuple FIXED_HTS_FEE_TUPLE =
            Tuple.of(10L, FUNGIBLE_TOKEN_HEADLONG_ADDRESS, false, false, OWNER_HEADLONG_ADDRESS);
    private final Tuple FIXED_HTS_FEE_WITH_SAME_TOKEN_FOR_PAYMENT_TUPLE =
            Tuple.of(10L, zeroAddress, false, true, OWNER_HEADLONG_ADDRESS);
    private final Tuple FRACTIONAL_FEE_TUPLE = Tuple.of(1L, 1L, 1L, 0L, false, OWNER_HEADLONG_ADDRESS);
    private final Tuple ROYALTY_FEE_TUPLE = Tuple.of(1L, 1L, 0L, zeroAddress, false, OWNER_HEADLONG_ADDRESS);
    private final Tuple ROYALTY_FEE_WITH_FALLBACK_TUPLE =
            Tuple.of(1L, 1L, 10L, FUNGIBLE_TOKEN_HEADLONG_ADDRESS, false, OWNER_HEADLONG_ADDRESS);
    private final Tuple ROYALTY_FEE_WITH_HBAR_FALLBACK_TUPLE =
            Tuple.of(1L, 1L, 10L, zeroAddress, true, OWNER_HEADLONG_ADDRESS);
    private final Tuple[] EMPTY_TOKEN_FEES_TUPLE_ARR = new Tuple[] {};

    // Expected decoded output fees
    private final CustomFee FIXED_HBAR_FEES = CustomFee.newBuilder()
            .fixedFee(FixedFee.newBuilder().amount(10).build())
            .feeCollectorAccountId(OWNER_ID)
            .build();
    private final CustomFee FIXED_HTS_FEES = CustomFee.newBuilder()
            .fixedFee(FixedFee.newBuilder()
                    .denominatingTokenId(FUNGIBLE_TOKEN_ID)
                    .amount(10)
                    .build())
            .feeCollectorAccountId(OWNER_ID)
            .build();
    private final CustomFee FRACTIONAL_FEE = CustomFee.newBuilder()
            .fractionalFee(FractionalFee.newBuilder()
                    .fractionalAmount(
                            Fraction.newBuilder().numerator(1L).denominator(1L).build())
                    .minimumAmount(1L)
                    .maximumAmount(0)
                    .netOfTransfers(false)
                    .build())
            .feeCollectorAccountId(OWNER_ID)
            .build();
    private final CustomFee ROYALTY_FEE = CustomFee.newBuilder()
            .royaltyFee(RoyaltyFee.newBuilder()
                    .exchangeValueFraction(
                            Fraction.newBuilder().denominator(1L).numerator(1L).build())
                    .build())
            .feeCollectorAccountId(OWNER_ID)
            .build();
    private final CustomFee ROYALTY_FEE_WITH_FALLBACK = CustomFee.newBuilder()
            .royaltyFee(RoyaltyFee.newBuilder()
                    .exchangeValueFraction(
                            Fraction.newBuilder().denominator(1L).numerator(1L).build())
                    .fallbackFee(FixedFee.newBuilder()
                            .amount(10L)
                            .denominatingTokenId(FUNGIBLE_TOKEN_ID)
                            .build())
                    .build())
            .feeCollectorAccountId(OWNER_ID)
            .build();
    private final CustomFee ROYALTY_FEE_WITH_HBAR_FALLBACK = CustomFee.newBuilder()
            .royaltyFee(RoyaltyFee.newBuilder()
                    .exchangeValueFraction(
                            Fraction.newBuilder().denominator(1L).numerator(1L).build())
                    .fallbackFee(FixedFee.newBuilder().amount(10L).build())
                    .build())
            .feeCollectorAccountId(OWNER_ID)
            .build();

    @BeforeEach
    void setUp() {
        subject = new UpdateTokenCustomFeesDecoder();
        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomHBarFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {FIXED_HBAR_FEE_TUPLE},
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), FIXED_HBAR_FEES);
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomHBarAndHTSFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {FIXED_HBAR_FEE_TUPLE, FIXED_HTS_FEE_TUPLE},
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 2);
        assertTrue(updateTokenCustomFees.customFees().contains(FIXED_HBAR_FEES));
        assertTrue(updateTokenCustomFees.customFees().contains(FIXED_HTS_FEES));
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomFixedFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HTS_FEE_TUPLE,
                                    FIXED_HTS_FEE_WITH_SAME_TOKEN_FOR_PAYMENT_TUPLE
                                },
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 3);
        assertTrue(updateTokenCustomFees.customFees().contains(FIXED_HBAR_FEES));
        assertTrue(updateTokenCustomFees.customFees().containsAll(List.of(FIXED_HTS_FEES, FIXED_HTS_FEES)));
    }

    @Test
    void decodeValidUpdateFungibleTokenCustom10FixedFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE
                                },
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 10);
        assertTrue(updateTokenCustomFees.customFees().contains(FIXED_HBAR_FEES));
    }

    @Test
    void decodeValidUpdateFungibleTokenCustom11FixedFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE,
                                    FIXED_HBAR_FEE_TUPLE
                                },
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        setConfiguration();

        final var error =
                assertThrows(HandleException.class, () -> subject.decodeUpdateFungibleTokenCustomFees(attempt));
        assertEquals(ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG, error.getStatus());
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomHTSFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {FIXED_HTS_FEE_TUPLE},
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), FIXED_HTS_FEES);
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomHTSSameTokenForPaymentFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                new Tuple[] {FIXED_HTS_FEE_WITH_SAME_TOKEN_FOR_PAYMENT_TUPLE},
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), FIXED_HTS_FEES);
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomFractionalFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                EMPTY_TOKEN_FEES_TUPLE_ARR,
                                new Tuple[] {FRACTIONAL_FEE_TUPLE}))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), FRACTIONAL_FEE);
    }

    @Test
    void decodeValidUpdateNonFungibleTokenCustomRoyaltyFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION
                                .encodeCallWithArgs(
                                        NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                        EMPTY_TOKEN_FEES_TUPLE_ARR,
                                        new Tuple[] {ROYALTY_FEE_TUPLE}))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateNonFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), NON_FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), ROYALTY_FEE);
    }

    @Test
    void decodeValidUpdateNonFungibleTokenCustom11RoyaltyFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION
                                .encodeCallWithArgs(
                                        NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, EMPTY_TOKEN_FEES_TUPLE_ARR, new Tuple[] {
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE
                                        }))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        setConfiguration();

        final var error =
                assertThrows(HandleException.class, () -> subject.decodeUpdateNonFungibleTokenCustomFees(attempt));
        assertEquals(ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG, error.getStatus());
    }

    @Test
    void decodeValidUpdateNonFungibleTokenCustom11MixedFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION
                                .encodeCallWithArgs(
                                        NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                        new Tuple[] {
                                            FIXED_HBAR_FEE_TUPLE,
                                            FIXED_HBAR_FEE_TUPLE,
                                            FIXED_HBAR_FEE_TUPLE,
                                            FIXED_HBAR_FEE_TUPLE,
                                            FIXED_HBAR_FEE_TUPLE
                                        },
                                        new Tuple[] {
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE,
                                            ROYALTY_FEE_TUPLE
                                        }))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        setConfiguration();

        final var error =
                assertThrows(HandleException.class, () -> subject.decodeUpdateNonFungibleTokenCustomFees(attempt));
        assertEquals(ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG, error.getStatus());
    }

    @Test
    void decodeValidUpdateNonFungibleTokenCustomRoyaltyWithFallbackFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION
                                .encodeCallWithArgs(
                                        NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                        EMPTY_TOKEN_FEES_TUPLE_ARR,
                                        new Tuple[] {ROYALTY_FEE_WITH_FALLBACK_TUPLE}))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateNonFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), NON_FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), ROYALTY_FEE_WITH_FALLBACK);
    }

    @Test
    void decodeValidUpdateNonFungibleTokenCustomRoyaltyWithHbarFallbackFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION
                                .encodeCallWithArgs(
                                        NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                        EMPTY_TOKEN_FEES_TUPLE_ARR,
                                        new Tuple[] {ROYALTY_FEE_WITH_HBAR_FALLBACK_TUPLE}))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateNonFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), NON_FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 1);
        assertEquals(updateTokenCustomFees.customFees().get(0), ROYALTY_FEE_WITH_HBAR_FALLBACK);
    }

    @Test
    void decodeValidUpdateFungibleTokenCustomResetFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.encodeCallWithArgs(
                                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                EMPTY_TOKEN_FEES_TUPLE_ARR,
                                EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 0);
    }

    @Test
    void decodeValidUpdateNonFungibleTokenCustomResetFeesRequest() {
        // Given a valid encoded update token custom fees request
        final var encoded = Bytes.wrapByteBuffer(
                        UpdateTokenCustomFeesTranslator.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION
                                .encodeCallWithArgs(
                                        NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                                        EMPTY_TOKEN_FEES_TUPLE_ARR,
                                        EMPTY_TOKEN_FEES_TUPLE_ARR))
                .toArray();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        setConfiguration();

        // When decoding the request
        final var body = subject.decodeUpdateNonFungibleTokenCustomFees(attempt);
        final var updateTokenCustomFees = body.tokenFeeScheduleUpdate();

        // Then the result should match the expected decoded request
        assertEquals(updateTokenCustomFees.tokenId(), NON_FUNGIBLE_TOKEN_ID);
        assertEquals(updateTokenCustomFees.customFees().size(), 0);
    }

    private void setConfiguration() {
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxCustomFeesAllowed()).willReturn(10);
    }
}
