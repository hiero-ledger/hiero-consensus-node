// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

public class VirtualMapConfigExtensionTest {

    @Test
    void verifyConfigExtension() {
        VirtualMapConfigExtension extension = new VirtualMapConfigExtension();

        assertEquals(3, extension.getConfigDataTypes().size());
        assertEquals(
                Set.of(VirtualMapConfig.class, VirtualMapLearnerSyncConfig.class, VirtualMapTeacherSyncConfig.class),
                extension.getConfigDataTypes());
    }
}
