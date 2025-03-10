// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofKeysAccessorImplTest {
    private static final long CONSTRUCTION_ID = 42;
    private static final Bytes MESSAGE = Bytes.wrap("In Manchua territory half is slough");
    private static final HistoryLibrary HISTORY_LIBRARY = new HistoryLibraryImpl();

    @TempDir
    private Path tempDir;

    @Test
    void anySubjectLookingAtSameTssKeysPathWillGetSameSchnorrKeyPairs() {
        final var firstSubject = newSubject();
        final var sig = firstSubject.sign(CONSTRUCTION_ID, MESSAGE);
        final var secondSubject = newSubject();
        final var keyPair = secondSubject.getOrCreateSchnorrKeyPair(CONSTRUCTION_ID);
        assertTrue(HISTORY_LIBRARY.verifySchnorr(sig, MESSAGE, keyPair.publicKey()));
    }

    private ProofKeysAccessorImpl newSubject() {
        return new ProofKeysAccessorImpl(HISTORY_LIBRARY, () -> HederaTestConfigBuilder.create()
                .withValue("tss.tssKeysPath", tempDir.toString())
                .getOrCreateConfig());
    }
}
