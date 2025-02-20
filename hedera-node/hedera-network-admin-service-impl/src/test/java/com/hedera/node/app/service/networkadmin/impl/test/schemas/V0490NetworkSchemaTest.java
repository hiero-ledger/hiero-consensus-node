// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.schemas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.hedera.node.app.service.networkadmin.impl.schemas.V0490NetworkSchema;
import org.junit.jupiter.api.Test;

public class V0490NetworkSchemaTest {
    @Test
    void registersExpectedSchema() {
        assertDoesNotThrow(V0490NetworkSchema::new);
    }
}
