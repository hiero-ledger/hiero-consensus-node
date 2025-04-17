// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.junit.jupiter.api.Test;

public class RunningHashTest {
    private static final String HASH_NOT_MATCH_MSG = "Hash doesn't match";

    @Test
    void setHashTest() throws Exception {
        RunningHash runningHash = new RunningHash();
        assertNull(runningHash.getHash(), "after initialization, the Hash should be null");
        Hash hash = CryptoRandomUtils.randomHash();
        runningHash.setHash(hash);
        assertEquals(hash, runningHash.getHash(), HASH_NOT_MATCH_MSG);
        assertEquals(hash, runningHash.getFutureHash().get(), HASH_NOT_MATCH_MSG);
    }

    @Test
    void initializeTest() throws Exception {
        Hash hash = CryptoRandomUtils.randomHash();
        RunningHash runningHash = new RunningHash(hash);

        assertEquals(hash, runningHash.getHash(), HASH_NOT_MATCH_MSG);
        assertEquals(hash, runningHash.getFutureHash().get(), HASH_NOT_MATCH_MSG);
    }
}
