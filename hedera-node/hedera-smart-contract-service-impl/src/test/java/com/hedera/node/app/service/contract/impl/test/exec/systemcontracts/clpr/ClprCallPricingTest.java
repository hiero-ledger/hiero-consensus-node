// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallPricing;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCallPricingTest {
    private static final long TARGET_TINYCENTS = 1_000_000_000L;
    private static final long NATIVE_GAS = 127_520L;

    @Mock
    private MessageFrame frame;

    @Mock
    private HederaOperations operations;

    static Stream<Arguments> gasRequirements() {
        return Stream.of(
                Arguments.of(600_000L, Wei.of(2), NATIVE_GAS, 123L, 299_877L),
                Arguments.of(300_001L, Wei.of(2), NATIVE_GAS, 123L, 149_878L),
                Arguments.of(254_999L, Wei.of(2), NATIVE_GAS, 0L, NATIVE_GAS),
                Arguments.of(270_000L, Wei.of(2), NATIVE_GAS, 10_000L, NATIVE_GAS),
                Arguments.of(10L, Wei.of(3), 0L, 0L, 4L),
                Arguments.of(0L, Wei.ONE, NATIVE_GAS, 0L, NATIVE_GAS),
                Arguments.of(Long.MAX_VALUE, Wei.ONE, NATIVE_GAS, 0L, Long.MAX_VALUE),
                Arguments.of(Long.MAX_VALUE, Wei.of(2), 0L, 0L, 4_611_686_018_427_387_904L),
                Arguments.of(Long.MAX_VALUE, Wei.of(3), 0L, 0L, 3_074_457_345_618_258_603L),
                Arguments.of(Long.MAX_VALUE, Wei.of(2), NATIVE_GAS, Long.MAX_VALUE, NATIVE_GAS),
                Arguments.of(
                        Long.MAX_VALUE,
                        Wei.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                        NATIVE_GAS,
                        0L,
                        NATIVE_GAS));
    }

    @ParameterizedTest
    @MethodSource("gasRequirements")
    void convertsEntireTargetToGasWithCeilingAndNativeFloor(
            final long targetTinybars,
            final Wei gasPrice,
            final long nativeGas,
            final long alreadyChargedGas,
            final long expectedGas) {
        given(frame.getGasPrice()).willReturn(gasPrice);
        given(operations.valueInTinybars(TARGET_TINYCENTS)).willReturn(targetTinybars);

        final var gas =
                ClprCallPricing.gasRequirement(frame, operations, TARGET_TINYCENTS, nativeGas, alreadyChargedGas);

        assertThat(gas).isEqualTo(expectedGas);
    }

    @Test
    void zeroGasPriceUsesNativeFloorWithoutCurrencyConversion() {
        given(frame.getGasPrice()).willReturn(Wei.ZERO);

        assertThat(ClprCallPricing.gasRequirement(frame, operations, TARGET_TINYCENTS, NATIVE_GAS, 123L))
                .isEqualTo(NATIVE_GAS);
        verifyNoInteractions(operations);
    }

    @Test
    void unrepresentableCurrencyConversionRequiresMaximumGas() {
        given(frame.getGasPrice()).willReturn(Wei.ONE);
        given(operations.valueInTinybars(TARGET_TINYCENTS)).willThrow(new ArithmeticException("amount exceeds long"));

        assertThat(ClprCallPricing.gasRequirement(frame, operations, TARGET_TINYCENTS, NATIVE_GAS, 100L))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void negativeConvertedAmountRequiresMaximumGas() {
        given(frame.getGasPrice()).willReturn(Wei.ONE);
        given(operations.valueInTinybars(TARGET_TINYCENTS)).willReturn(-1L);

        assertThat(ClprCallPricing.gasRequirement(frame, operations, TARGET_TINYCENTS, NATIVE_GAS, 100L))
                .isEqualTo(Long.MAX_VALUE);
    }
}
