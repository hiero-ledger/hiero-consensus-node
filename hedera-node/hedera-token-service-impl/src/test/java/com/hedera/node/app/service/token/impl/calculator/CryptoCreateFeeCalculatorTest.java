// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.hooks.*;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
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
    private FeeContext feeContext;

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
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var op = CryptoCreateTransactionBody.newBuilder().build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            assertThat(result).isNotNull();
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with simple ED25519 key")
        void calculateTxFeeWithSimpleKey() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(2);
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Node = 100000 + 1000000 (1 extra signature) = 1100000
            // Network = node * multiplier = 1100000 * 9 = 9900000
            assertThat(result.getNodeTotalTinycents()).isEqualTo(1100000L);
            assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(9900000L);
        }

        @Test
        @DisplayName("calculateTxFee with KeyList containing multiple keys")
        void calculateTxFeeWithKeyList() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
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
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Base fee (499M) + 2 extra keys beyond includedCount=1 (2 * 100M = 200M)
            // service = 499000000 + 200000000 = 699000000
            assertThat(result.getServiceTotalTinycents()).isEqualTo(699000000L);
        }

        @Test
        @DisplayName("calculateTxFee with ThresholdKey")
        void calculateTxFeeWithThresholdKey() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(3);
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
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // service = 499000000 + (3-1)*100000000 = 699000000
            assertThat(result.getServiceTotalTinycents()).isEqualTo(699000000L);
        }

        @Test
        @DisplayName("calculateTxFee with keys exceeding included count triggers overage")
        void calculateTxFeeWithKeysOverage() {
            // Given: Create a fee schedule where only 1 key is included, extras cost 100M each
            final var scheduleWithLowKeyLimit = FeeSchedule.DEFAULT
                    .copyBuilder()
                    .node(NodeFee.newBuilder()
                            .baseFee(100000L)
                            .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                            .build())
                    .network(NetworkFee.newBuilder().multiplier(9).build())
                    .extras(
                            makeExtraDef(Extra.SIGNATURES, 1000000L),
                            makeExtraDef(Extra.KEYS, 100000000L), // 100M per key
                            makeExtraDef(Extra.STATE_BYTES, 110L))
                    .services(makeService(
                            "CryptoService",
                            makeServiceFee(
                                    HederaFunctionality.CRYPTO_CREATE,
                                    499000000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoCreateFeeCalculator()));
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

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
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Base fee (499000000) + overage for 4 extra keys (4 * 100000000 = 400000000)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(899000000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with keys exactly at included count has no overage")
        void calculateTxFeeWithKeysAtIncludedCount() {
            // Given: Create a fee schedule where only 1 key is included
            final var scheduleWithLowKeyLimit = FeeSchedule.DEFAULT
                    .copyBuilder()
                    .node(NodeFee.newBuilder()
                            .baseFee(100000L)
                            .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                            .build())
                    .network(NetworkFee.newBuilder().multiplier(9).build())
                    .extras(
                            makeExtraDef(Extra.SIGNATURES, 1000000L),
                            makeExtraDef(Extra.KEYS, 100000000L),
                            makeExtraDef(Extra.STATE_BYTES, 110L))
                    .services(makeService(
                            "CryptoService",
                            makeServiceFee(
                                    HederaFunctionality.CRYPTO_CREATE,
                                    499000000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoCreateFeeCalculator()));
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

            // Create exactly 1 key (at the included count boundary)
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Only base fee, no overage
            assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with one hook charges hook fee")
        void calculateTxFeeWithOneHook() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var hook = createHookDetails(1L);
            final var op = CryptoCreateTransactionBody.newBuilder()
                    .hookCreationDetails(hook)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Base fee (499M) + 1 hook (10M) = 509M
            assertThat(result.getServiceTotalTinycents()).isEqualTo(509000000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with multiple hooks charges per hook")
        void calculateTxFeeWithMultipleHooks() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var hook1 = createHookDetails(1L);
            final var hook2 = createHookDetails(2L);
            final var hook3 = createHookDetails(3L);
            final var op = CryptoCreateTransactionBody.newBuilder()
                    .hookCreationDetails(hook1, hook2, hook3)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Base fee (499M) + 3 hooks (30M) = 529M
            assertThat(result.getServiceTotalTinycents()).isEqualTo(529000000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("verify getTransactionType returns CRYPTO_CREATE_ACCOUNT")
        void verifyTransactionType() {
            // Given
            final var calculator = new CryptoCreateFeeCalculator();

            // When
            final var txnType = calculator.getTransactionType();

            // Then
            assertThat(txnType).isEqualTo(TransactionBody.DataOneOfType.CRYPTO_CREATE_ACCOUNT);
        }
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(9).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.HOOK_UPDATES, 10000000L),
                        makeExtraDef(Extra.STATE_BYTES, 110L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_CREATE,
                                499000000L,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.HOOK_UPDATES, 0))))
                .build();
    }

    private static HookCreationDetails createHookDetails(long id) {
        final var spec = EvmHookSpec.newBuilder()
                .contractId(ContractID.newBuilder().contractNum(321).build())
                .build();
        final var evmHook = EvmHook.newBuilder().spec(spec).build();
        return HookCreationDetails.newBuilder()
                .hookId(id)
                .extensionPoint(HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK)
                .evmHook(evmHook)
                .build();
    }
}
