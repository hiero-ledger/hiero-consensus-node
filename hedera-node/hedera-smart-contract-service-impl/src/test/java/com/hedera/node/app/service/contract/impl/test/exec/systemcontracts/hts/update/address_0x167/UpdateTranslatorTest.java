// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update.address_0x167;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder.FAILURE_CUSTOMIZER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPLICITLY_IMMUTABLE_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MUTABLE_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class UpdateTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ReadableTokenStore readableTokenStore;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private ContractMetrics contractMetrics;

    private UpdateTranslator subject;

    private final UpdateDecoder decoder = new UpdateDecoder();

    private final Tuple hederaToken = Tuple.from(
            "name",
            "symbol",
            NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
            "memo",
            true,
            1000L,
            false,
            // TokenKey
            new Tuple[] {},
            // Expiry
            Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L));

    @BeforeEach
    void setUp() {
        subject = new UpdateTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void failureCustomizerDetectsImmutableTokenWithNullAdminKey() {
        final var body = updateWithTreasuryId();
        given(nativeOperations.readableTokenStore()).willReturn(readableTokenStore);
        // Has no admin key
        given(readableTokenStore.get(FUNGIBLE_TOKEN_ID)).willReturn(FUNGIBLE_TOKEN);
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_SIGNATURE, mockEnhancement());
        assertEquals(TOKEN_IS_IMMUTABLE, translatedStatus);
    }

    @Test
    void failureCustomizerDetectsImmutableTokenWithExplicitlyImmutableAdminKey() {
        final var body = updateWithTreasuryId();
        given(nativeOperations.readableTokenStore()).willReturn(readableTokenStore);
        // Has no admin key
        given(readableTokenStore.get(FUNGIBLE_TOKEN_ID)).willReturn(EXPLICITLY_IMMUTABLE_FUNGIBLE_TOKEN);
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_SIGNATURE, mockEnhancement());
        assertEquals(TOKEN_IS_IMMUTABLE, translatedStatus);
    }

    @Test
    void failureCustomizerDoesNotChangeWithMutableToken() {
        final var body = updateWithTreasuryId();
        given(nativeOperations.readableTokenStore()).willReturn(readableTokenStore);
        // Has no admin key
        given(readableTokenStore.get(FUNGIBLE_TOKEN_ID)).willReturn(MUTABLE_FUNGIBLE_TOKEN);
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_SIGNATURE, mockEnhancement());
        assertEquals(INVALID_SIGNATURE, translatedStatus);
    }

    @Test
    void failureCustomizerDoesNotChangeWithMissingToken() {
        final var body = updateWithTreasuryId();
        given(nativeOperations.readableTokenStore()).willReturn(readableTokenStore);
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_SIGNATURE, mockEnhancement());
        assertEquals(INVALID_SIGNATURE, translatedStatus);
    }

    @Test
    void failureCustomizerDetectsInvalidTreasuryAccountId() {
        final var body = updateWithTreasuryId();
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_ACCOUNT_ID, mockEnhancement());
        assertEquals(INVALID_TREASURY_ACCOUNT_FOR_TOKEN, translatedStatus);
    }

    @Test
    void failureCustomizerIgnoresTreasuryAccountIdIfNotSet() {
        final var body = updateWithoutTreasuryId();
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_ACCOUNT_ID, mockEnhancement());
        assertEquals(INVALID_ACCOUNT_ID, translatedStatus);
    }

    @Test
    void failureCustomizerDoesNothingForInvalidTokenId() {
        final var body = updateWithoutTreasuryId();
        final var translatedStatus = FAILURE_CUSTOMIZER.customize(body, INVALID_TOKEN_ID, mockEnhancement());
        assertEquals(INVALID_TOKEN_ID, translatedStatus);
        verifyNoInteractions(nativeOperations);
    }

    @Test
    void matchesUpdateV1Test() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_INFO_FUNCTION_V1, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesUpdateV2Test() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_INFO_FUNCTION_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesUpdateV3Test() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_INFO_FUNCTION_V3, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsOnIncorrectSelector() {
        attempt = createHtsCallAttempt(FreezeUnfreezeTranslator.FREEZE, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void matchesFailsOnUpdateMetadataTest() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromUpdateTest() {
        Tuple tuple = Tuple.of(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken);
        Bytes inputBytes = Bytes.wrapByteBuffer(TOKEN_UPDATE_INFO_FUNCTION_V1.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.isSelector(TOKEN_UPDATE_INFO_FUNCTION_V1)).willReturn(true);
        lenient().when(attempt.isSelector(TOKEN_UPDATE_INFO_FUNCTION_V2)).thenReturn(false);
        lenient().when(attempt.isSelector(TOKEN_UPDATE_INFO_FUNCTION_V3)).thenReturn(false);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(NON_SYSTEM_ACCOUNT_ID);
        given(addressIdConverter.convert(any())).willReturn(NON_SYSTEM_ACCOUNT_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(DispatchForResponseCodeHtsCall.class);
    }

    private TransactionBody updateWithTreasuryId() {
        final var op = TokenUpdateTransactionBody.newBuilder()
                .token(FUNGIBLE_TOKEN_ID)
                .treasury(NON_SYSTEM_ACCOUNT_ID)
                .build();
        return TransactionBody.newBuilder().tokenUpdate(op).build();
    }

    private TransactionBody updateWithoutTreasuryId() {
        final var op =
                TokenUpdateTransactionBody.newBuilder().token(FUNGIBLE_TOKEN_ID).build();
        return TransactionBody.newBuilder().tokenUpdate(op).build();
    }
}
