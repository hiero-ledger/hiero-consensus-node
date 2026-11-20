// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KEY_ENCODING;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.RejectedUpdateCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateKeysTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

class RejectedUpdateCallTest extends CallTestBase {
    private static final long UPDATE_GAS = 123_456L;
    private static final Tuple EMPTY_EXPIRY = Tuple.of(0L, ZERO_ADDRESS, 0L);
    private static final Tuple EXPLICIT_SUPPLY_KEY =
            Tuple.of(BigInteger.valueOf(16), Tuple.of(false, ZERO_ADDRESS, new byte[32], new byte[0], ZERO_ADDRESS));
    private static final Tuple INHERITED_SUPPLY_KEY =
            Tuple.of(BigInteger.valueOf(16), Tuple.of(true, ZERO_ADDRESS, new byte[0], new byte[0], ZERO_ADDRESS));

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry registry = new SystemContractMethodRegistry();

    @Test
    void revertsWithInvalidKeyEncodingAndCanonicalUpdateGasWithoutDispatching() {
        givenRejectableAttempt();
        given(gasCalculator.canonicalGasRequirement(DispatchType.UPDATE)).willReturn(UPDATE_GAS);

        final var pricedResult = new RejectedUpdateCall(attempt, INVALID_KEY_ENCODING).execute(frame);

        final var fullResult = pricedResult.fullResult();
        assertEquals(MessageFrame.State.REVERT, fullResult.result().state());
        assertEquals(
                Bytes.wrap(INVALID_KEY_ENCODING.protoName().getBytes()),
                fullResult.result().output());
        assertEquals(UPDATE_GAS, fullResult.gasRequirement());
        assertEquals(INVALID_KEY_ENCODING, pricedResult.responseCode());
        verifyNoInteractions(systemContractOperations);
    }

    @Test
    void hasNoSchedulableForm() {
        givenRejectableAttempt();

        final var call = new RejectedUpdateCall(attempt, INVALID_KEY_ENCODING);

        assertThrows(UnsupportedOperationException.class, call::asSchedulableDispatchIn);
    }

    static Stream<Arguments> inheritedKeyUpdates() {
        final var updateDecoder167 = new UpdateDecoder();
        final var updateDecoder16c =
                new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c
                        .UpdateDecoder();
        final var keys = new Tuple[] {EXPLICIT_SUPPLY_KEY, INHERITED_SUPPLY_KEY};
        final Object[] tokenFields = {"name", "", ZERO_ADDRESS, "", true, 1000L, false, keys, EMPTY_EXPIRY};
        return Stream.of(
                Arguments.of(
                        "0x167 updateTokenKeys",
                        (TranslatorFactory)
                                (registry, metrics) -> new UpdateKeysTranslator(updateDecoder167, registry, metrics),
                        UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION,
                        keys),
                Arguments.of(
                        "0x167 updateTokenInfo V1",
                        (TranslatorFactory)
                                (registry, metrics) -> new UpdateTranslator(updateDecoder167, registry, metrics),
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V1,
                        Tuple.from(tokenFields)),
                Arguments.of(
                        "0x167 updateTokenInfo V2",
                        (TranslatorFactory)
                                (registry, metrics) -> new UpdateTranslator(updateDecoder167, registry, metrics),
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V2,
                        Tuple.from(tokenFields)),
                Arguments.of(
                        "0x167 updateTokenInfo V3",
                        (TranslatorFactory)
                                (registry, metrics) -> new UpdateTranslator(updateDecoder167, registry, metrics),
                        UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3,
                        Tuple.from(tokenFields)),
                Arguments.of(
                        "0x16c updateTokenKeys",
                        (TranslatorFactory) (registry, metrics) ->
                                new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update
                                        .address_0x16c.UpdateKeysTranslator(updateDecoder16c, registry, metrics),
                        com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c
                                .UpdateKeysTranslator.TOKEN_UPDATE_KEYS_16C,
                        keys),
                Arguments.of(
                        "0x16c updateTokenInfo with metadata",
                        (TranslatorFactory) (registry, metrics) ->
                                new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update
                                        .address_0x16c.UpdateTranslator(updateDecoder16c, registry, metrics),
                        com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c
                                .UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA,
                        Tuple.from("name", "", ZERO_ADDRESS, "", true, 1000L, false, keys, EMPTY_EXPIRY, new byte[0])));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inheritedKeyUpdates")
    void translatorRevertsInsteadOfDispatchingWhenAnyKeyIsInherited(
            final String name,
            final TranslatorFactory translatorFactory,
            final SystemContractMethod method,
            final Object secondArg) {
        givenDecodableAttempt();
        givenRejectableAttempt();
        given(gasCalculator.canonicalGasRequirement(DispatchType.UPDATE)).willReturn(UPDATE_GAS);
        lenient()
                .when(attempt.isSelector(any(SystemContractMethod.class)))
                .thenAnswer(inv -> inv.getArgument(0) == method);
        given(attempt.input())
                .willReturn(
                        Bytes.wrapByteBuffer(method.encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, secondArg)));

        final var call = translatorFactory.create(registry, contractMetrics).callFrom(attempt);
        final var fullResult = call.execute(frame).fullResult();

        assertThat(call).isInstanceOf(RejectedUpdateCall.class);
        assertEquals(MessageFrame.State.REVERT, fullResult.result().state());
        assertEquals(
                Bytes.wrap(INVALID_KEY_ENCODING.protoName().getBytes()),
                fullResult.result().output());
        assertEquals(UPDATE_GAS, fullResult.gasRequirement());
        verifyNoInteractions(systemContractOperations);
    }

    @FunctionalInterface
    interface TranslatorFactory {
        AbstractCallTranslator<HtsCallAttempt> create(SystemContractMethodRegistry registry, ContractMetrics metrics);
    }

    private void givenRejectableAttempt() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
    }

    private void givenDecodableAttempt() {
        // Lenient: rejection happens before any key address is converted
        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
        lenient().when(addressIdConverter.convert(ZERO_ADDRESS)).thenReturn(ZERO_ACCOUNT_ID);
        lenient().when(attempt.nativeOperations()).thenReturn(nativeOperations);
        lenient().when(nativeOperations.entityIdFactory()).thenReturn(entityIdFactory);
    }
}
