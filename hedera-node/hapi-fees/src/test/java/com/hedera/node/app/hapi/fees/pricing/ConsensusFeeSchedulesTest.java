// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConsensusFeeSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForSubmitMessageSubyptes() throws IOException {
        testCanonicalPriceFor(ConsensusSubmitMessage, DEFAULT);
    }
}
