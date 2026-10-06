// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.SemanticVersion;
import org.junit.jupiter.api.Test;

public class V0560BlockStreamSchemaTest {
    private final V0560BlockStreamSchema subject = new V0560BlockStreamSchema();

    @Test
    void versionIsV0560() {
        assertEquals(new SemanticVersion(0, 56, 0, "", ""), subject.getVersion());
    }

    @Test
    void createsOneSingleton() {
        final var stateDefs = subject.statesToCreate(DEFAULT_CONFIG);
        assertEquals(1, stateDefs.size());
        final var def = stateDefs.iterator().next();
        assertTrue(def.singleton());
        assertEquals(BLOCK_STREAM_INFO_KEY, def.stateKey());
        assertEquals(BLOCK_STREAM_INFO_STATE_ID, def.stateId());
    }
}
