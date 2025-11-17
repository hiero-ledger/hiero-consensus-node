// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
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
 * Unit tests for {@link CryptoUpdateFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoUpdateFeeCalculatorTest {

    @Mock
    private CalculatorState calculatorState;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        final var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new CryptoUpdateFeeCalculator()));
    }

    @Nested
    @DisplayName("CryptoUpdate Fee Calculation Tests")
    class CryptoUpdateTests {
        @Test
        @DisplayName("calculateTxFee with no key update")
        void calculateTxFeeWithNoKeyUpdate() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .memo("Updated memo")
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Only base fee + network/node fees, no key extras
            assertThat(result).isNotNull();
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.service).isEqualTo(5000000L); // baseFee from config
            assertThat(result.network).isEqualTo(200000L);
        }

        @Test
        @DisplayName("calculateTxFee with simple ED25519 key update")
        void calculateTxFeeWithSimpleKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(2);
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Base fee + 0 extra keys (includedCount=1 in config, so 1 key is free)
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.service).isEqualTo(5000000L);
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
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Base fee (5M) + 2 extra keys beyond includedCount=1 (2 * 100M = 200M)
            // service = 5000000 + 200000000 = 205000000
            assertThat(result.service).isEqualTo(205000000L);
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
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Base fee + 2 extra keys (3 total, 1 included)
            // service = 5000000 + 200000000 = 205000000
            assertThat(result.service).isEqualTo(205000000L);
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
                                    HederaFunctionality.CRYPTO_UPDATE,
                                    5000000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoUpdateFeeCalculator()));
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
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Base fee (5M) + overage for 4 extra keys (4 * 100000000 = 400000000)
            assertThat(result.service).isEqualTo(405000000L);
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
                                    HederaFunctionality.CRYPTO_UPDATE,
                                    5000000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoUpdateFeeCalculator()));
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            // Create exactly 1 key (at the included count boundary)
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Only base fee, no overage
            assertThat(result.service).isEqualTo(5000000L);
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(200000L);
        }

        @Test
        @DisplayName("verify getTransactionType returns CRYPTO_UPDATE_ACCOUNT")
        void verifyTransactionType() {
            // Given
            final var calculator = new CryptoUpdateFeeCalculator();

            // When
            final var txnType = calculator.getTransactionType();

            // Then
            assertThat(txnType).isEqualTo(TransactionBody.DataOneOfType.CRYPTO_UPDATE_ACCOUNT);
        }
    }

    // Helper method to create test fee schedule using values from simpleFeesSchedules.json
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
                                HederaFunctionality.CRYPTO_UPDATE,
                                5000000L, // baseFee from config
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1)))) // 1 key included
                .build();
    }
}
