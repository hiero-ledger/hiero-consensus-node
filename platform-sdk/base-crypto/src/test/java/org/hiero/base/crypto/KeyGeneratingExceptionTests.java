// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.CAUSE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.MESSAGE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionSame;

import org.junit.jupiter.api.Test;

class KeyGeneratingExceptionTests {

    @Test
    void testKeyGeneratingException() {
        assertExceptionSame(new KeyGeneratingException(MESSAGE, CAUSE), MESSAGE, CAUSE);
    }
}
