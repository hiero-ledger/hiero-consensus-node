// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class V0790HistorySchemaTest {
    private final V0790HistorySchema subject = new V0790HistorySchema();

    @Test
    void onlyRemovesTheWrapsProvingKeyHash() {
        assertTrue(subject.statesToCreate().isEmpty());
        assertEquals(Set.of(WRAPS_PROVING_KEY_HASH_STATE_ID), subject.statesToRemove());
    }
}
