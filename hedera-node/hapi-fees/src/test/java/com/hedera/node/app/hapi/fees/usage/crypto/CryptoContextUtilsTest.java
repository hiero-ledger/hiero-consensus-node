// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CryptoContextUtilsTest {
    @Test
    void getsChangedKeys() {
        final Map<Long, Long> newMap = new HashMap<>();
        final Map<Long, Long> existingMap = new HashMap<>();

        newMap.put(1L, 2L);
        newMap.put(3L, 2L);
        newMap.put(4L, 2L);

        existingMap.put(1L, 2L);
        existingMap.put(4L, 2L);
        existingMap.put(5L, 2L);

        assertEquals(1, CryptoContextUtils.getChangedCryptoKeys(newMap.keySet(), existingMap.keySet()));
    }

    @Test
    void getsChangedTokenKeys() {
        final Map<AllowanceId, Long> newMap = new HashMap<>();
        final Map<AllowanceId, Long> existingMap = new HashMap<>();

        newMap.put(new AllowanceId(1L, 2L), 2L);
        newMap.put(new AllowanceId(2L, 2L), 2L);
        newMap.put(new AllowanceId(3L, 2L), 2L);

        existingMap.put(new AllowanceId(1L, 2L), 2L);
        existingMap.put(new AllowanceId(4L, 2L), 2L);
        existingMap.put(new AllowanceId(3L, 5L), 2L);

        assertEquals(2, CryptoContextUtils.getChangedTokenKeys(newMap.keySet(), existingMap.keySet()));
    }
}
