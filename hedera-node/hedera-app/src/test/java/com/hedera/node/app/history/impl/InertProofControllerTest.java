// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InertProofControllerTest {
    @Mock
    private WritableHistoryStore store;

    @Test
    void returnsGivenIdAndNoops() {
        final var subject = new InertProofController(123L);
        assertEquals(123L, subject.constructionId());
        assertFalse(subject.isStillInProgress(DEFAULT_CONFIG.getConfigData(TssConfig.class)));
        assertDoesNotThrow(() -> subject.advanceConstruction(
                Instant.EPOCH, Bytes.EMPTY, store, true, DEFAULT_CONFIG.getConfigData(TssConfig.class)));
        assertDoesNotThrow(
                () -> subject.addProofKeyPublication(new ProofKeyPublication(123L, Bytes.EMPTY, Instant.EPOCH)));
        assertDoesNotThrow(() -> subject.addProofVote(123L, HistoryProofVote.DEFAULT, store));
        assertDoesNotThrow(subject::cancelPendingWork);
    }
}
