// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;

import com.hederahashgraph.api.proto.java.HederaFunctionality;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class UtilOpsSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForUtilPrng() throws IOException {
        testCanonicalPriceFor(HederaFunctionality.UtilPrng, DEFAULT);
    }
}
