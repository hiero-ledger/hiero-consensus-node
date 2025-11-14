// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.*;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoCreateFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoCreateFeeCalculatorTest {

    @Mock
    private CalculatorState calculatorState;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        final var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new CryptoCreateFeeCalculator()));
    }

    @Nested
    @DisplayName("CryptoCreate Fee Calculation Tests")
    class CryptoCreateTests {
        @Test
        @DisplayName("calculateTxFee with no key")
        void calculateTxFeeWithNoKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
            final var op = CryptoCreateTransactionBody.newBuilder().build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            assertThat(result).isNotNull();
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.service).isEqualTo(498500000L);
            assertThat(result.network).isEqualTo(200000L);
        }

        @Test
        @DisplayName("calculateTxFee with simple ED25519 key")
        void calculateTxFeeWithSimpleKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(2);
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.service).isEqualTo(598500000L);
            assertThat(result.network).isEqualTo(200000L);
        }

        @Test
        @DisplayName("calculateTxFee with KeyList containing multiple keys")
        void calculateTxFeeWithKeyList() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
            final var keyList = KeyList.newBuilder()
                    .keys(
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build(),
                            Key.newBuilder()
                                    .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                    .build(),
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build())
                    .build();
            final var key = Key.newBuilder().keyList(keyList).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Same as other cases - addExtraFee adds unit fee regardless of key count
            // service=498500000 + 3x100000000 = 798500000
            assertThat(result.service).isEqualTo(798500000L);
        }

        @Test
        @DisplayName("calculateTxFee with ThresholdKey")
        void calculateTxFeeWithThresholdKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(3);
            final var thresholdKey = ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap(new byte[32]))
                                            .build(),
                                    Key.newBuilder()
                                            .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap(new byte[32]))
                                            .build())
                            .build())
                    .build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // service=498500000 + 3x100000000 = 798500000
            assertThat(result.service).isEqualTo(798500000L);
        }

        @Test
        @DisplayName("calculateTxFee with keys exceeding included count triggers overage")
        void calculateTxFeeWithKeysOverage() {
            // Given: Create a fee schedule where only 1 key is included, extras cost 100M each
            final var scheduleWithLowKeyLimit = FeeSchedule.DEFAULT
                    .copyBuilder()
                    .node(NodeFee.newBuilder()
                            .baseFee(100000L)
                            .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                            .build())
                    .network(NetworkFee.newBuilder().multiplier(2).build())
                    .extras(
                            makeExtraDef(Extra.SIGNATURES, 1000000L),
                            makeExtraDef(Extra.KEYS, 100000000L), // 100M per key
                            makeExtraDef(Extra.BYTES, 110L))
                    .services(makeService(
                            "CryptoService",
                            makeServiceFee(
                                    HederaFunctionality.CRYPTO_CREATE,
                                    498500000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoCreateFeeCalculator()));
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            // Create a KeyList with 5 keys (4 over the included count of 1)
            final var keyList = KeyList.newBuilder()
                    .keys(
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build(),
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build(),
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build(),
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build(),
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build())
                    .build();
            final var key = Key.newBuilder().keyList(keyList).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Base fee (498500000) + overage for 4 extra keys (4 * 100000000 = 400000000)
            assertThat(result.service).isEqualTo(898500000L);
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(200000L);
        }

        @Test
        @DisplayName("calculateTxFee with keys exactly at included count has no overage")
        void calculateTxFeeWithKeysAtIncludedCount() {
            // Given: Create a fee schedule where only 1 key is included
            final var scheduleWithLowKeyLimit = FeeSchedule.DEFAULT
                    .copyBuilder()
                    .node(NodeFee.newBuilder()
                            .baseFee(100000L)
                            .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                            .build())
                    .network(NetworkFee.newBuilder().multiplier(2).build())
                    .extras(
                            makeExtraDef(Extra.SIGNATURES, 1000000L),
                            makeExtraDef(Extra.KEYS, 100000000L),
                            makeExtraDef(Extra.BYTES, 110L))
                    .services(makeService(
                            "CryptoService",
                            makeServiceFee(
                                    HederaFunctionality.CRYPTO_CREATE,
                                    498500000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoCreateFeeCalculator()));
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            // Create exactly 1 key (at the included count boundary)
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Only base fee, no overage
            assertThat(result.service).isEqualTo(498500000L);
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(200000L);
        }
    }

    // Helper method to create test fee schedule using real production values from simpleFeesSchedules.json
    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.BYTES, 110L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_CREATE,
                                498500000L,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 0))))
                .build();
    }
}
