// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.schemas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V0490FileSchemaTest {

    @Test
    void throttlesDevJsonParses() throws IOException {
        try (InputStream in =
                V0490FileSchema.class.getClassLoader().getResourceAsStream("genesis/throttles-dev.json")) {
            assertNotNull(in, "genesis/throttles-dev.json should be on the classpath");
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertNotNull(assertDoesNotThrow(() -> V0490FileSchema.parseThrottleDefinitions(json)));
        }
    }
}
