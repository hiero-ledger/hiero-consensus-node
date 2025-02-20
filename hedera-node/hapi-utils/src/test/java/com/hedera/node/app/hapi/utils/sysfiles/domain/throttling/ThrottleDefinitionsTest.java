// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import com.hedera.node.app.hapi.utils.TestUtils;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ThrottleDefinitionsTest {
    @Test
    void factoryWorks() throws IOException {
        // given:
        final var proto = TestUtils.protoDefs("bootstrap/throttles.json");

        // expect:
        Assertions.assertEquals(proto, ThrottleDefinitions.fromProto(proto).toProto());
    }
}
