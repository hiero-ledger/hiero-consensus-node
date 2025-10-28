// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
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
 * Tests for CryptoCreate fee model calculations.
 */
class CryptoCreateFeeModelTests {

    @Test
    void cryptoCreate() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_CREATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 22
        // node base fee = 0 (1 sig included)
        // network = node * 2 = 0
        assertEquals(22, fee.total());
    }

    @Test
    void cryptoCreateWithKey() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_CREATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 22
        // 1 key is included in base fee (per HIP-1261), so (1 - 1) = 0 extra keys charged
        // node + network = 0 (1 sig included)
        assertEquals(22, fee.total());
    }
}
