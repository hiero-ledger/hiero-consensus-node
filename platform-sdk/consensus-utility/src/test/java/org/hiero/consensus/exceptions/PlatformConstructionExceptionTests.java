// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.exceptions;

import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.CAUSE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.CAUSE_MESSAGE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.MESSAGE;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionContains;
import static org.hiero.base.utility.test.fixtures.assertions.ExceptionAssertions.assertExceptionSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformConstructionExceptionTests {

    @Test
    void testPlatformConstructionException() {
        assertExceptionSame(new PlatformConstructionException(MESSAGE, CAUSE), MESSAGE, CAUSE);
        assertExceptionContains(new PlatformConstructionException(CAUSE), List.of(CAUSE_MESSAGE), CAUSE);
    }
}
