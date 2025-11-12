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
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
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

            // Then: Real production values from simpleFeesSchedules.json
            // keys = 0, includedCount=1, chargedKeys = max(0, 0-1) = 0, no extra charge
            // node=100000, network=200000, service=499700000
            assertThat(result).isNotNull();
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.service).isEqualTo(499700000L);
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

            // Then: 1 key, includedCount=1, chargedKeys = max(0, 1-1) = 0, no extra charge
            // node=100000, network=200000, service=499700000
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.service).isEqualTo(499700000L);
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

            // Then: 3 keys, includedCount=1, chargedKeys = max(0, 3-1) = 2
            // extra = 2 × 100000000 = 200000000
            // service = 499700000 + 200000000 = 699700000
            assertThat(result.service).isEqualTo(699700000L);
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

            // Then: ThresholdKey with 3 keys, includedCount=1, chargedKeys = max(0, 3-1) = 2
            // extra = 2 × 100000000 = 200000000
            // service = 499700000 + 200000000 = 699700000
            assertThat(result.service).isEqualTo(699700000L);
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
                        makeExtraDef(Extra.SIGNATURES, 10000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.BYTES, 100L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_CREATE,
                                499700000L,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1))))
                .build();
    }
}
