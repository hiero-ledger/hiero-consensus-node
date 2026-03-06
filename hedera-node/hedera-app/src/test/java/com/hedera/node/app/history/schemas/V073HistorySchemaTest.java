// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V073HistorySchema.WRAPS_PROVING_KEY_HASH_KEY;
import static com.hedera.node.app.history.schemas.V073HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class V073HistorySchemaTest {
    @Test
    void definesExpectedSingleton() {
        final var schema = new V073HistorySchema();
        final var statesToCreate = schema.statesToCreate();

        assertEquals(1, statesToCreate.size());
        final var def = statesToCreate.iterator().next();
        assertEquals(WRAPS_PROVING_KEY_HASH_KEY, def.stateKey());
        assertEquals(WRAPS_PROVING_KEY_HASH_STATE_ID, def.stateId());
        assertTrue(def.singleton());
    }
}
