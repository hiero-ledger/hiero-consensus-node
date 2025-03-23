// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.exceptions;

import static com.swirlds.platform.exceptions.ExceptionAssertions.CAUSE;
import static com.swirlds.platform.exceptions.ExceptionAssertions.CAUSE_MESSAGE;
import static com.swirlds.platform.exceptions.ExceptionAssertions.MESSAGE;
import static com.swirlds.platform.exceptions.ExceptionAssertions.assertExceptionContains;
import static com.swirlds.platform.exceptions.ExceptionAssertions.assertExceptionSame;

import com.swirlds.platform.crypto.KeyCertPurpose;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeyLoadingException;
import com.swirlds.platform.gossip.shadowgraph.SyncTimeoutException;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.system.PlatformConstructionException;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class ExceptionTests {

    @Test
    void testKeyGeneratingException() {
        assertExceptionSame(new KeyGeneratingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
    }

    @Test
    void testKeyLoadingException() {
        assertExceptionSame(new KeyLoadingException(MESSAGE), MESSAGE, null);
        assertExceptionSame(new KeyLoadingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionSame(new KeyLoadingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionContains(
                new KeyLoadingException(MESSAGE, KeyCertPurpose.SIGNING, NodeId.FIRST_NODE_ID),
                List.of((NodeId.FIRST_NODE_ID.id() + 1) + "", MESSAGE),
                null);
    }

    @Test
    void testSyncTimeoutException() {
        assertExceptionContains(
                new SyncTimeoutException(Duration.ofSeconds(61), Duration.ofSeconds(60)),
                List.of("sync time exceeded", "60 sec", "61 sec"),
                null);
    }

    @Test
    void testPlatformConstructionException() {
        assertExceptionSame(new PlatformConstructionException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionContains(new PlatformConstructionException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }

    @Test
    void testNetworkProtocolException() {
        assertExceptionSame(new NetworkProtocolException(MESSAGE), MESSAGE, null);
        assertExceptionContains(new NetworkProtocolException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }
}
