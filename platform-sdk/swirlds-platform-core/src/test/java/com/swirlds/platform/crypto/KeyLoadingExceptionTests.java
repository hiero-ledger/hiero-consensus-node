// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.CAUSE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.MESSAGE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionContains;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionSame;

import java.util.List;
import org.hiero.consensus.crypto.KeyCertPurpose;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class KeyLoadingExceptionTests {

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
}
