// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.node.base.SubType.DEFAULT;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class UtilOpsSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForUtilPrng() throws IOException {
        testCanonicalPriceFor(UTIL_PRNG, DEFAULT);
    }
}
