// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DurationUtilsTest {
    @Test
    void isLongerTest() {
        Assertions.assertTrue(
                DurationUtils.isLonger(Duration.ofSeconds(2), Duration.ofSeconds(1)), "2 should be bigger than 1");
        Assertions.assertFalse(
                DurationUtils.isLonger(Duration.ofSeconds(1), Duration.ofSeconds(1)), "1==1, so not longer");
        Assertions.assertFalse(
                DurationUtils.isLonger(Duration.ofSeconds(1), Duration.ofSeconds(2)), "1 < 2, so not longer");
    }

    @Test
    void maxDurationTest() {
        Assertions.assertEquals(
                Duration.ofSeconds(2),
                DurationUtils.max(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                "2 should be the max");
        Assertions.assertEquals(
                Duration.ofSeconds(2),
                DurationUtils.max(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                "2 should be the max");
        Assertions.assertEquals(
                Duration.ofSeconds(1),
                DurationUtils.max(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                "1 should be the max");
    }
}
