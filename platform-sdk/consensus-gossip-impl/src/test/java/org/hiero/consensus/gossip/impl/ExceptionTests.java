// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl;

import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.CAUSE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.CAUSE_MESSAGE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.MESSAGE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionContains;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionSame;

import java.time.Duration;
import java.util.List;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncTimeoutException;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.junit.jupiter.api.Test;

class ExceptionTests {

    @Test
    void testSyncTimeoutException() {
        assertExceptionContains(
                new SyncTimeoutException(Duration.ofSeconds(61), Duration.ofSeconds(60)),
                List.of("sync time exceeded", "60 sec", "61 sec"),
                null);
    }

    @Test
    void testNetworkProtocolException() {
        assertExceptionSame(new NetworkProtocolException(MESSAGE), MESSAGE, null);
        assertExceptionContains(new NetworkProtocolException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }
}
