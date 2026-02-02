// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.hooks.*;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
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
    private FeeContext feeContext;

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
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .memo("Updated memo")
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Only base fee + network/node fees, no key extras
            assertThat(result).isNotNull();
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getServiceTotalTinycents()).isEqualTo(1200000L); // baseFee from production config
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with simple ED25519 key update")
        void calculateTxFeeWithSimpleKey() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(2);
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee + 0 extra keys (includedCount=1 in config, so 1 key is free)
            // Node = 100000 + 1000000 (1 extra signature) = 1100000
            // Network = node * multiplier = 1100000 * 9 = 9900000
            assertThat(result.getNodeTotalTinycents()).isEqualTo(1100000L);
            assertThat(result.getServiceTotalTinycents()).isEqualTo(1200000L);
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
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (1.2M) + 2 extra keys beyond includedCount=1 (2 * 100M = 200M)
            // service = 1200000 + 200000000 = 201200000
            assertThat(result.getServiceTotalTinycents()).isEqualTo(201200000L);
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
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee + 2 extra keys (3 total, 1 included)
            // service = 1200000 + 200000000 = 201200000
            assertThat(result.getServiceTotalTinycents()).isEqualTo(201200000L);
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
                            makeExtraDef(Extra.BYTES, 110L))
                    .services(makeService(
                            "CryptoService",
                            makeServiceFee(
                                    HederaFunctionality.CRYPTO_UPDATE,
                                    1200000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoUpdateFeeCalculator()));
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
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (1.2M) + overage for 4 extra keys (4 * 100000000 = 400000000)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(401200000L);
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
                            makeExtraDef(Extra.BYTES, 110L))
                    .services(makeService(
                            "CryptoService",
                            makeServiceFee(
                                    HederaFunctionality.CRYPTO_UPDATE,
                                    1200000L,
                                    makeExtraIncluded(Extra.KEYS, 1)))) // Only 1 key included
                    .build();

            feeCalculator =
                    new SimpleFeeCalculatorImpl(scheduleWithLowKeyLimit, Set.of(new CryptoUpdateFeeCalculator()));
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

            // Create exactly 1 key (at the included count boundary)
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .key(key)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Only base fee, no overage
            assertThat(result.getServiceTotalTinycents()).isEqualTo(1200000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with hook creations charges hook fee")
        void calculateTxFeeWithHookCreations() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var hook1 = createHookDetails(1L);
            final var hook2 = createHookDetails(2L);
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .hookCreationDetails(hook1, hook2)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (1.2M) + 2 hooks (20M) = 21.2M
            assertThat(result.getServiceTotalTinycents()).isEqualTo(21200000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with hook deletions charges hook fee")
        void calculateTxFeeWithHookDeletions() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .hookIdsToDelete(100L, 200L)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (1.2M) + 2 hook deletions (20M) = 21.2M
            assertThat(result.getServiceTotalTinycents()).isEqualTo(21200000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with hook creations and deletions charges for both")
        void calculateTxFeeWithHookCreationsAndDeletions() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var hook = createHookDetails(3L);
            final var op = CryptoUpdateTransactionBody.newBuilder()
                    .accountIDToUpdate(AccountID.newBuilder().accountNum(1001L).build())
                    .hookCreationDetails(hook)
                    .hookIdsToDelete(100L)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoUpdateAccount(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (1.2M) + 1 creation + 1 deletion (20M) = 21.2M
            assertThat(result.getServiceTotalTinycents()).isEqualTo(21200000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
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
                        makeExtraDef(Extra.BYTES, 110L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_UPDATE,
                                1200000L, // baseFee from production config
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.HOOK_UPDATES, 0)))) // No hooks included
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
