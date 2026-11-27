// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KEY_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.InheritedKeyUpdateCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateKeysTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.token.ReadableTokenStore;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

class InheritedKeyUpdateCallTest extends CallTestBase {
    private static final long UPDATE_GAS = 123_456L;
    private static final long CANONICAL_UPDATE_GAS = 654_321L;
    private static final Address SENDER_ADDRESS = Address.fromHexString("0x0000000000000000000000000000000000000400");
    private static final Tuple EMPTY_EXPIRY = Tuple.of(0L, ZERO_ADDRESS, 0L);
    private static final Tuple EXPLICIT_SUPPLY_KEY =
            Tuple.of(BigInteger.valueOf(16), Tuple.of(false, ZERO_ADDRESS, new byte[32], new byte[0], ZERO_ADDRESS));
    private static final Tuple INHERITED_SUPPLY_KEY =
            Tuple.of(BigInteger.valueOf(16), Tuple.of(true, ZERO_ADDRESS, new byte[0], new byte[0], ZERO_ADDRESS));
    private static final TransactionBody BODY = TransactionBody.newBuilder()
            .tokenUpdate(TokenUpdateTransactionBody.newBuilder()
                    .token(FUNGIBLE_TOKEN_ID)
                    .name("renamed"))
            .build();

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry registry = new SystemContractMethodRegistry();

    @Test
    void revertsAnUpdateThatWouldSucceedWithInvalidKeyEncoding() {
        givenDispatchableAttempt();
        givenDispatchWith(SUCCESS);

        final var pricedResult = new InheritedKeyUpdateCall(attempt, BODY).execute(frame);

        final var fullResult = pricedResult.fullResult();
        assertEquals(MessageFrame.State.REVERT, fullResult.result().state());
        assertEquals(
                Bytes.wrap(UInt256.valueOf(INVALID_KEY_ENCODING.protoOrdinal())),
                fullResult.result().output());
        assertEquals(UPDATE_GAS, fullResult.gasRequirement());
        assertEquals(INVALID_KEY_ENCODING, pricedResult.responseCode());
        assertEquals(INVALID_KEY_ENCODING, recordBuilder.status());
    }

    @Test
    void keepsInvalidTokenIdOfAFailedUpdate() {
        givenDispatchableAttempt();
        givenDispatchWith(INVALID_TOKEN_ID);

        assertFailedWithoutRevert(new InheritedKeyUpdateCall(attempt, BODY).execute(frame), INVALID_TOKEN_ID);
    }

    @Test
    void keepsInvalidSignatureOfAFailedUpdate() {
        givenDispatchableAttempt();
        givenDispatchWith(INVALID_SIGNATURE);
        given(nativeOperations.readableTokenStore()).willReturn(tokenStore);

        assertFailedWithoutRevert(new InheritedKeyUpdateCall(attempt, BODY).execute(frame), INVALID_SIGNATURE);
    }

    @Test
    void rejectsSchedulingWithCanonicalUpdateGasWithoutDispatching() {
        givenAttempt();
        given(gasCalculator.canonicalGasRequirement(DispatchType.UPDATE)).willReturn(CANONICAL_UPDATE_GAS);

        final var pricedResult = new InheritedKeyUpdateCall(attempt, BODY).rejectScheduling();

        final var fullResult = pricedResult.fullResult();
        assertEquals(MessageFrame.State.REVERT, fullResult.result().state());
        assertEquals(
                Bytes.wrap(INVALID_KEY_ENCODING.protoName().getBytes()),
                fullResult.result().output());
        assertEquals(CANONICAL_UPDATE_GAS, fullResult.gasRequirement());
        assertEquals(INVALID_KEY_ENCODING, pricedResult.responseCode());
        verifyNoInteractions(systemContractOperations);
    }

    @Test
    void hasNoSchedulableForm() {
        givenAttempt();

        final var call = new InheritedKeyUpdateCall(attempt, BODY);

        assertThrows(UnsupportedOperationException.class, call::asSchedulableDispatchIn);
    }

    static Stream<Arguments> updates() {
        final var updateDecoder167 = new UpdateDecoder();
        final var updateDecoder16c =
                new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c
                        .UpdateDecoder();
        final var mixed = new Tuple[] {EXPLICIT_SUPPLY_KEY, INHERITED_SUPPLY_KEY};
        final var explicit = new Tuple[] {EXPLICIT_SUPPLY_KEY};
        final TranslatorFactory keys167 =
                (registry, metrics) -> new UpdateKeysTranslator(updateDecoder167, registry, metrics);
        final TranslatorFactory info167 =
                (registry, metrics) -> new UpdateTranslator(updateDecoder167, registry, metrics);
        final TranslatorFactory keys16c =
                (registry, metrics) -> new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update
                        .address_0x16c.UpdateKeysTranslator(updateDecoder16c, registry, metrics);
        final TranslatorFactory info16c = (registry, metrics) ->
                new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c
                        .UpdateTranslator(updateDecoder16c, registry, metrics);
        final var keysMethod16c = com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update
                .address_0x16c.UpdateKeysTranslator.TOKEN_UPDATE_KEYS_16C;
        final var infoMethod16c = com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update
                .address_0x16c.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA;
        return Stream.of(
                Arguments.of(
                        "0x167 updateTokenKeys", keys167, UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION, mixed, true),
                Arguments.of(
                        "0x167 updateTokenKeys, explicit key",
                        keys167,
                        UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION,
                        explicit,
                        false),
                Arguments.of(
                        "0x167 updateTokenInfo V1",
                        info167,
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V1,
                        token(mixed),
                        true),
                Arguments.of(
                        "0x167 updateTokenInfo V2",
                        info167,
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V2,
                        token(mixed),
                        true),
                Arguments.of(
                        "0x167 updateTokenInfo V3",
                        info167,
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3,
                        token(mixed),
                        true),
                Arguments.of(
                        "0x167 updateTokenInfo V3, explicit key",
                        info167,
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3,
                        token(explicit),
                        false),
                Arguments.of("0x16c updateTokenKeys", keys16c, keysMethod16c, mixed, true),
                Arguments.of("0x16c updateTokenKeys, explicit key", keys16c, keysMethod16c, explicit, false),
                Arguments.of(
                        "0x16c updateTokenInfo with metadata", info16c, infoMethod16c, tokenWithMetadata(mixed), true),
                Arguments.of(
                        "0x16c updateTokenInfo with metadata, explicit key",
                        info16c,
                        infoMethod16c,
                        tokenWithMetadata(explicit),
                        false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updates")
    void translatorUsesInheritedKeyCallOnlyWhenAnyKeyIsInherited(
            final String name,
            final TranslatorFactory translatorFactory,
            final SystemContractMethod method,
            final Object secondArg,
            final boolean inherited) {
        givenAttempt();
        lenient().when(addressIdConverter.convert(ZERO_ADDRESS)).thenReturn(ZERO_ACCOUNT_ID);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        lenient()
                .when(attempt.isSelector(any(SystemContractMethod.class)))
                .thenAnswer(inv -> inv.getArgument(0) == method);
        given(attempt.input())
                .willReturn(
                        Bytes.wrapByteBuffer(method.encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, secondArg)));

        final var call = translatorFactory.create(registry, contractMetrics).callFrom(attempt);

        assertThat(call).isInstanceOf(DispatchForResponseCodeHtsCall.class);
        assertEquals(inherited, call instanceof InheritedKeyUpdateCall);
    }

    @FunctionalInterface
    interface TranslatorFactory {
        AbstractCallTranslator<HtsCallAttempt> create(SystemContractMethodRegistry registry, ContractMetrics metrics);
    }

    private static Tuple token(final Tuple[] keys) {
        return Tuple.from("name", "", ZERO_ADDRESS, "", true, 1000L, false, keys, EMPTY_EXPIRY);
    }

    private static Tuple tokenWithMetadata(final Tuple[] keys) {
        return Tuple.from("name", "", ZERO_ADDRESS, "", true, 1000L, false, keys, EMPTY_EXPIRY, new byte[0]);
    }

    private static void assertFailedWithoutRevert(final PricedResult pricedResult, final ResponseCodeEnum status) {
        final var fullResult = pricedResult.fullResult();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, fullResult.result().state());
        assertEquals(
                Bytes.wrap(UInt256.valueOf(status.protoOrdinal())),
                fullResult.result().output());
        assertEquals(UPDATE_GAS, fullResult.gasRequirement());
        assertEquals(status, pricedResult.responseCode());
    }

    private void givenAttempt() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderAddress()).willReturn(SENDER_ADDRESS);
        given(addressIdConverter.convertSender(SENDER_ADDRESS)).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
    }

    private void givenDispatchableAttempt() {
        givenAttempt();
        given(gasCalculator.gasRequirement(BODY, DispatchType.UPDATE, SENDER_ID))
                .willReturn(UPDATE_GAS);
    }

    private void givenDispatchWith(final ResponseCodeEnum status) {
        final var current = new AtomicReference<>(status);
        given(recordBuilder.status()).willAnswer(inv -> current.get());
        lenient()
                .doAnswer(inv -> {
                    current.set(inv.getArgument(0));
                    return recordBuilder;
                })
                .when(recordBuilder)
                .status(any());
        given(systemContractOperations.dispatch(
                        eq(BODY), eq(verificationStrategy), eq(SENDER_ID), eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
    }
}
