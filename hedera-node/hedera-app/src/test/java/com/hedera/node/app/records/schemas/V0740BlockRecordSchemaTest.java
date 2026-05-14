// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SemanticVersion;
import org.junit.jupiter.api.Test;

class V0740BlockRecordSchemaTest {
    private final V0740BlockRecordSchema subject = new V0740BlockRecordSchema();

    @Test
    void versionIsV0740() {
        assertEquals(new SemanticVersion(0, 74, 0, "", ""), subject.getVersion());
    }
}
