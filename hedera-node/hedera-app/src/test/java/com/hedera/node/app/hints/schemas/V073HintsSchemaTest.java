// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SemanticVersion;
import org.junit.jupiter.api.Test;

class V073HintsSchemaTest {
    private final V073HintsSchema subject = new V073HintsSchema();

    @Test
    void versionIsV073() {
        assertEquals(new SemanticVersion(0, 73, 0, "", ""), subject.getVersion());
    }
}
