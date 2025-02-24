// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_AUTO_RENEW;
import static com.hedera.hapi.node.base.SubType.DEFAULT;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ContractFeeSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForContractAutoRenew() throws IOException {
        testCanonicalPriceFor(CONTRACT_AUTO_RENEW, DEFAULT, 0.00001);
    }
}
