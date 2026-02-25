// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.exceptions;

import static com.swirlds.platform.test.fixtures.ExceptionAssertions.CAUSE;
import static com.swirlds.platform.test.fixtures.ExceptionAssertions.CAUSE_MESSAGE;
import static com.swirlds.platform.test.fixtures.ExceptionAssertions.MESSAGE;
import static com.swirlds.platform.test.fixtures.ExceptionAssertions.assertExceptionContains;
import static com.swirlds.platform.test.fixtures.ExceptionAssertions.assertExceptionSame;

import com.swirlds.platform.crypto.KeyLoadingException;
import java.util.List;
import org.hiero.consensus.crypto.KeyCertPurpose;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.exceptions.PlatformConstructionException;
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
    void testPlatformConstructionException() {
        assertExceptionSame(new PlatformConstructionException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionContains(new PlatformConstructionException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }
}
