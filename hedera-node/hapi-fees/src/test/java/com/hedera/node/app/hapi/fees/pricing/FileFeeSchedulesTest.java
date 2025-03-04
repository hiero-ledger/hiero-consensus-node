// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.SubType.DEFAULT;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FileFeeSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForBaseAppend() throws IOException {
        testCanonicalPriceFor(FILE_APPEND, DEFAULT);
    }
}
