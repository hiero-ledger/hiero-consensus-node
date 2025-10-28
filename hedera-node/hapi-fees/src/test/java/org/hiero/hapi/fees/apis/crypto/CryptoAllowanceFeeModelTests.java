// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
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
 * Tests for CryptoApproveAllowance and CryptoDeleteAllowance fee model calculations.
 */
class CryptoAllowanceFeeModelTests {

    @Test
    void cryptoApproveAllowance() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_APPROVE_ALLOWANCE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ALLOWANCES, 1L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 20
        // 1 allowance included
        assertEquals(20, fee.total());
    }

    @Test
    void cryptoApproveAllowanceMultiple() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_APPROVE_ALLOWANCE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ALLOWANCES, 5L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 20
        // (5 - 1) allowances * 2000 = 8000
        assertEquals(20 + 8000, fee.total());
    }

    @Test
    void cryptoDeleteAllowance() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_DELETE_ALLOWANCE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.ALLOWANCES, 1L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 15
        assertEquals(15, fee.total());
    }
}
