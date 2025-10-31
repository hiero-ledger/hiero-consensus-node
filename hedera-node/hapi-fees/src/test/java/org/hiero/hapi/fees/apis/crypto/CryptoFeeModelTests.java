// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static org.hiero.hapi.fees.apis.crypto.CryptoFeeTestUtils.createTestFeeSchedule;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.support.fees.Extra;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for crypto fee model calculations across various transaction types.
 * Validates that the fee models correctly compute fees based on transaction parameters.
 */
class CryptoFeeModelTests {

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cryptoFeeTestCases")
    @DisplayName("Crypto fee model calculations")
    // package-private for test framework access
    void testCryptoFeeCalculation(
            HederaFunctionality functionality, final String description, Map<Extra, Long> params, long expectedTotal) {
        final var feeSchedule = createTestFeeSchedule();
        final var model = FeeModelRegistry.lookupModel(functionality);
        final var fee = model.computeFee(params, feeSchedule);
        assertEquals(expectedTotal, fee.total(), description);
    }

    // package-private for @MethodSource
    static Stream<Arguments> cryptoFeeTestCases() {
        return Stream.concat(
                Stream.concat(
                        Stream.concat(cryptoCreateTestCases(), cryptoUpdateAndDeleteTestCases()),
                        cryptoTransferTestCases()),
                Stream.concat(cryptoAllowanceTestCases(), cryptoQueryTestCases()));
    }

    private static Stream<Arguments> cryptoCreateTestCases() {
        return Stream.of(
                Arguments.of(CRYPTO_CREATE, "Create with no key", Map.of(Extra.SIGNATURES, 1L, Extra.KEYS, 0L), 22L),
                Arguments.of(
                        CRYPTO_CREATE,
                        "Create with 1 key (included)",
                        Map.of(Extra.SIGNATURES, 1L, Extra.KEYS, 1L),
                        22L),
                Arguments.of(
                        CRYPTO_CREATE,
                        "Create with 2 signatures",
                        Map.of(Extra.SIGNATURES, 2L, Extra.KEYS, 1L),
                        22L + 60_000_000L),
                Arguments.of(
                        CRYPTO_CREATE,
                        "Create with many signatures (15)",
                        Map.of(Extra.SIGNATURES, 15L, Extra.KEYS, 0L),
                        1_740_000_022L)); // service: 22 + 840M, node: 300M, network: 600M
    }

    private static Stream<Arguments> cryptoUpdateAndDeleteTestCases() {
        return Stream.of(
                // CryptoUpdate tests
                Arguments.of(CRYPTO_UPDATE, "Update without key", Map.of(Extra.SIGNATURES, 1L, Extra.KEYS, 0L), 22L),
                Arguments.of(
                        CRYPTO_UPDATE, "Update with key (included)", Map.of(Extra.SIGNATURES, 1L, Extra.KEYS, 1L), 22L),
                Arguments.of(
                        CRYPTO_UPDATE,
                        "Update with 3 signatures",
                        Map.of(Extra.SIGNATURES, 3L, Extra.KEYS, 1L),
                        22L + 120_000_000L),
                // CryptoDelete tests
                Arguments.of(CRYPTO_DELETE, "Delete with 1 signature", Map.of(Extra.SIGNATURES, 1L), 15L),
                Arguments.of(
                        CRYPTO_DELETE, "Delete with 2 signatures", Map.of(Extra.SIGNATURES, 2L), 15L + 60_000_000L));
    }

    private static Stream<Arguments> cryptoTransferTestCases() {
        return Stream.of(
                // CryptoTransfer tests
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Transfer basic (2 accounts)",
                        Map.of(
                                Extra.SIGNATURES, 1L,
                                Extra.ACCOUNTS, 2L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 0L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.NFT_SERIALS, 0L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 0L,
                                Extra.CREATED_ACCOUNTS, 0L),
                        18L + 3L), // base 18 + (2-1)*3
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Transfer with 5 accounts",
                        Map.of(
                                Extra.SIGNATURES, 1L,
                                Extra.ACCOUNTS, 5L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 0L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.NFT_SERIALS, 0L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 0L,
                                Extra.CREATED_ACCOUNTS, 0L),
                        18L + 12L), // base 18 + (5-1)*3
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Transfer with 3 fungible tokens",
                        Map.of(
                                Extra.SIGNATURES, 1L,
                                Extra.ACCOUNTS, 2L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 3L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.NFT_SERIALS, 0L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 0L,
                                Extra.CREATED_ACCOUNTS, 0L),
                        18L + 3L + 6L), // base + (2-1)*3 accounts + (3-1)*3 tokens
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Transfer with NFTs (2 types, 5 serials)",
                        Map.of(
                                Extra.SIGNATURES, 1L,
                                Extra.ACCOUNTS, 2L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 0L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 2L,
                                Extra.NFT_SERIALS, 5L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 0L,
                                Extra.CREATED_ACCOUNTS, 0L),
                        18L + 3L + 6L + 5L), // base + accounts + NFT types + serials
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Transfer with account creation (2 accounts)",
                        Map.of(
                                Extra.SIGNATURES, 1L,
                                Extra.ACCOUNTS, 3L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 0L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.NFT_SERIALS, 0L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 0L,
                                Extra.CREATED_ACCOUNTS, 2L),
                        18L + 6L + 6L), // base + (3-1)*3 accounts + 2*3 created accounts
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Transfer with auto-associations (3)",
                        Map.of(
                                Extra.SIGNATURES, 1L,
                                Extra.ACCOUNTS, 2L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 2L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.NFT_SERIALS, 0L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 3L,
                                Extra.CREATED_ACCOUNTS, 0L),
                        18L + 3L + 3L + 9L), // base + accounts + tokens + auto-assocs
                Arguments.of(
                        CRYPTO_TRANSFER,
                        "Complex transfer (all parameters)",
                        Map.of(
                                Extra.SIGNATURES, 2L,
                                Extra.ACCOUNTS, 4L,
                                Extra.STANDARD_FUNGIBLE_TOKENS, 3L,
                                Extra.STANDARD_NON_FUNGIBLE_TOKENS, 1L,
                                Extra.NFT_SERIALS, 2L,
                                Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 1L,
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L,
                                Extra.CREATED_AUTO_ASSOCIATIONS, 4L,
                                Extra.CREATED_ACCOUNTS, 2L),
                        60_000_059L));
    }

    private static Stream<Arguments> cryptoAllowanceTestCases() {
        return Stream.of(
                // CryptoApproveAllowance tests
                Arguments.of(
                        CRYPTO_APPROVE_ALLOWANCE,
                        "Approve 1 allowance",
                        Map.of(Extra.SIGNATURES, 1L, Extra.ALLOWANCES, 1L),
                        20L),
                Arguments.of(
                        CRYPTO_APPROVE_ALLOWANCE,
                        "Approve 5 allowances",
                        Map.of(Extra.SIGNATURES, 1L, Extra.ALLOWANCES, 5L),
                        20L + 8_000L), // base + (5-1)*2000
                // CryptoDeleteAllowance tests
                Arguments.of(
                        CRYPTO_DELETE_ALLOWANCE,
                        "Delete 1 allowance",
                        Map.of(Extra.SIGNATURES, 1L, Extra.ALLOWANCES, 1L),
                        15L),
                Arguments.of(
                        CRYPTO_DELETE_ALLOWANCE,
                        "Delete 3 allowances",
                        Map.of(Extra.SIGNATURES, 1L, Extra.ALLOWANCES, 3L),
                        15L + 4_000L)); // base + (3-1)*2000
    }

    private static Stream<Arguments> cryptoQueryTestCases() {
        return Stream.of(
                Arguments.of(CRYPTO_GET_INFO, "Get account info", Map.of(Extra.SIGNATURES, 1L), 10L),
                Arguments.of(CRYPTO_GET_ACCOUNT_RECORDS, "Get account records", Map.of(Extra.SIGNATURES, 1L), 15L));
    }
}
