// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractAutoRenew;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ContractFeeSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForContractAutoRenew() throws IOException {
        testCanonicalPriceFor(ContractAutoRenew, DEFAULT, 0.00001);
    }
}
