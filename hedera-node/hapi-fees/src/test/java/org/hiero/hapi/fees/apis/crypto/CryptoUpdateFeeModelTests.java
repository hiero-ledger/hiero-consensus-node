// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
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
 * Tests for CryptoUpdate fee model calculations.
 */
class CryptoUpdateFeeModelTests {

    @Test
    void cryptoUpdate() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_UPDATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 22 (no key update)
        // KEYS: 0 keys, 1 included, so (0-1) = -1, but floor is 0
        assertEquals(22, fee.total());
    }

    @Test
    void cryptoUpdateWithKey() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_UPDATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service base fee = 22
        // key is included (1), so no extra charge
        assertEquals(22, fee.total());
    }
}
