// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConsensusFeeSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForSubmitMessageSubyptes() throws IOException {
        testCanonicalPriceFor(CONSENSUS_SUBMIT_MESSAGE, DEFAULT);
    }
}
