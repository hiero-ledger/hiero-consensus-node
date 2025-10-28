// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static org.hiero.hapi.fees.apis.crypto.CryptoFeeTestUtils.createTestFeeSchedule;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.hiero.hapi.fees.FeeModel;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.junit.jupiter.api.Test;

/**
 * Tests for CryptoTransfer fee model calculations.
 */
class CryptoTransferFeeModelTests {

    @Test
    void cryptoTransferBasic() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 1L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 0L);
        params.put(Extra.CREATED_ACCOUNTS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // 1 account included, no overage
        assertEquals(18, fee.total());
    }

    @Test
    void cryptoTransferMultipleAccounts() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 5L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 0L);
        params.put(Extra.CREATED_ACCOUNTS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (5 - 1) accounts * 3 = 12
        assertEquals(18 + 12, fee.total());
    }

    @Test
    void cryptoTransferWithTokens() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 2L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 3L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 0L);
        params.put(Extra.CREATED_ACCOUNTS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (2 - 1) accounts * 3 = 3
        // (3 - 1) fungible tokens * 3 = 6
        assertEquals(18 + 3 + 6, fee.total());
    }

    @Test
    void cryptoTransferWithNFTs() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 2L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 2L);
        params.put(Extra.NFT_SERIALS, 5L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 0L);
        params.put(Extra.CREATED_ACCOUNTS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (2 - 1) accounts * 3 = 3
        // 2 NFT token types * 3 = 6
        // 5 NFT serials * 1 = 5
        assertEquals(18 + 3 + 6 + 5, fee.total());
    }

    @Test
    void cryptoTransferWithCreatedAccounts() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 2L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 0L);
        params.put(Extra.CREATED_ACCOUNTS, 2L); // 2 new accounts created via alias

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (2 - 1) accounts * 3 = 3
        // (2 - 0) created accounts * 3 = 6
        assertEquals(18 + 3 + 6, fee.total());
    }

    @Test
    void cryptoTransferWithAutoAssociations() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 2L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 2L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 3L); // 3 auto-associations created
        params.put(Extra.CREATED_ACCOUNTS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (2 - 1) accounts * 3 = 3
        // (2 - 1) fungible tokens * 3 = 3
        // (3 - 0) created auto-associations * 3 = 9
        assertEquals(18 + 3 + 3 + 9, fee.total());
    }

    /**
     * Tests a transfer that combines both account creation and token auto-associations.
     * This scenario simulates a transfer where a new account is created via alias
     * and tokens are sent to accounts that trigger automatic token associations.
     * Verifies that fees for both account creation and auto-associations are
     * correctly calculated together.
     */
    @Test
    void cryptoTransferWithCreatedAccountsAndAutoAssociations() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 3L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 2L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 2L); // 2 auto-associations
        params.put(Extra.CREATED_ACCOUNTS, 1L); // 1 new account

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (3 - 1) accounts * 3 = 6
        // (2 - 1) fungible tokens * 3 = 3
        // (2 - 0) created auto-associations * 3 = 6
        // (1 - 0) created accounts * 3 = 3
        assertEquals(18 + 6 + 3 + 6 + 3, fee.total());
    }

    @Test
    void cryptoTransferWithMultipleCreatedAccounts() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ACCOUNTS, 5L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.NFT_SERIALS, 0L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 0L);
        params.put(Extra.CREATED_ACCOUNTS, 5L); // 5 new accounts via aliases

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 18
        // (5 - 1) accounts * 3 = 12
        // (5 - 0) created accounts * 3 = 15
        assertEquals(18 + 12 + 15, fee.total());
    }

    /**
     * Tests a complex crypto transfer scenario that exercises all fee parameters simultaneously.
     * This scenario includes:
     * - Multiple signatures (2)
     * - Multiple HBAR account transfers (4)
     * - Mix of standard and custom-fee tokens (both fungible and NFTs)
     * - NFT serial transfers
     * - Account creation via aliases
     * - Token auto-associations
     *
     * <p>This comprehensive test ensures the fee calculation correctly handles all parameters
     * when they are combined in a realistic, complex transfer scenario.
     */
    @Test
    void cryptoTransferWithComplexScenario() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_TRANSFER);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 2L);
        params.put(Extra.ACCOUNTS, 4L);
        params.put(Extra.STANDARD_FUNGIBLE_TOKENS, 3L);
        params.put(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 1L);
        params.put(Extra.NFT_SERIALS, 2L);
        params.put(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 1L);
        params.put(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0L);
        params.put(Extra.CREATED_AUTO_ASSOCIATIONS, 4L); // 4 auto-associations
        params.put(Extra.CREATED_ACCOUNTS, 2L); // 2 new accounts

        FeeResult fee = model.computeFee(params, feeSchedule);

        // Node fee: 0 (2 signatures < 10 included in node)
        // Network fee: 0 * 2 = 0
        // Service fee breakdown:
        //   - Base: 18
        //   - (2 - 1) signatures * 60000000 = 60000000 (service includes 1 sig)
        //   - (4 - 1) accounts * 3 = 9
        //   - (3 - 1) standard fungible tokens * 3 = 6
        //   - (1 - 0) standard NFT tokens * 3 = 3
        //   - (2 - 0) NFT serials * 1 = 2
        //   - (1 - 0) custom fee fungible tokens * 3 = 3
        //   - (4 - 0) created auto-associations * 3 = 12
        //   - (2 - 0) created accounts * 3 = 6
        // Total: 0 (node) + 0 (network) + 60000059 (service) = 60000059
        assertEquals(60000059, fee.total());
    }
}
