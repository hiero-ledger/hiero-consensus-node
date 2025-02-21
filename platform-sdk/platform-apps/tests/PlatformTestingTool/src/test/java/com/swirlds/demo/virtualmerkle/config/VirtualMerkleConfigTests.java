// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class VirtualMerkleConfigTests {

    @Test
    void validateDefaults() {
        final VirtualMerkleConfig config = new VirtualMerkleConfig();
        assertFalse(config.isAssorted(), "Assorted is false by default");
        assertEquals(0.0, config.getSamplingProbability(), "Sampling probability is 0 by default");
    }
}
