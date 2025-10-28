// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_INFO;
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
 * Tests for crypto query fee model calculations.
 */
class CryptoQueryFeeModelTests {

    @Test
    void cryptoGetInfo() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_GET_INFO);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 10
        assertEquals(10, fee.total());
    }

    @Test
    void cryptoGetAccountRecords() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_GET_ACCOUNT_RECORDS);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 15
        assertEquals(15, fee.total());
    }
}
