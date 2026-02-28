// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V072HistorySchema.EXPECTED_WRAPS_PROVING_KEY_HASH_KEY;
import static com.hedera.node.app.history.schemas.V072HistorySchema.EXPECTED_WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class V072HistorySchemaTest {
    @Test
    void definesExpectedSingleton() {
        final var schema = new V072HistorySchema();
        final var statesToCreate = schema.statesToCreate();

        assertEquals(1, statesToCreate.size());
        final var def = statesToCreate.iterator().next();
        assertEquals(EXPECTED_WRAPS_PROVING_KEY_HASH_KEY, def.stateKey());
        assertEquals(EXPECTED_WRAPS_PROVING_KEY_HASH_STATE_ID, def.stateId());
        assertTrue(def.singleton());
    }
}
