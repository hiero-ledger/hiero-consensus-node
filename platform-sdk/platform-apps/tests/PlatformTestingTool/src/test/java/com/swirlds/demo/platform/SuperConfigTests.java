// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SuperConfigTests {

    @Test
    void fcmConfigIsNotNull() {
        final SuperConfig config = new SuperConfig();
        assertNotNull(config.getFcmConfig(), "FCMConfig should never be null");
    }
}
