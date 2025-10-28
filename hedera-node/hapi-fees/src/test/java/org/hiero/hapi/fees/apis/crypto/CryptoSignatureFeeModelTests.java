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
 * Tests for signature-related fee calculations across crypto operations.
 */
class CryptoSignatureFeeModelTests {

    @Test
    void multipleSignatures() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_CREATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 3L);
        params.put(Extra.KEYS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service: 22 + (3-1) * 60000000 = 120000022
        // node: 0 (3 < 10 included)
        // network: 0
        assertEquals(22 + 120000000, fee.total());
    }

    @Test
    void manySignatures() {
        final var feeSchedule = createTestFeeSchedule();
        FeeModel model = FeeModelRegistry.lookupModel(CRYPTO_CREATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 15L);
        params.put(Extra.KEYS, 0L);

        FeeResult fee = model.computeFee(params, feeSchedule);

        // service: 22 + (15-1) * 60000000 = 840000022
        // node: (15-10) * 60000000 = 300000000
        // network: node * 2 = 600000000
        assertEquals(22 + 840000000 + 900000000, fee.total());
    }
}
