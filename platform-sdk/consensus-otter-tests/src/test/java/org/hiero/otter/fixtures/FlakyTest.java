// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import org.junit.jupiter.api.Test;

class FlakyTest {

    @Test
    void flakyTest() {
        // This test is intentionally flaky to demonstrate.
        // It will fail approximately 50% of the time.
        if (Math.random() < 0.5) {
            throw new RuntimeException("Flaky test failed!");
        }
    }
}
