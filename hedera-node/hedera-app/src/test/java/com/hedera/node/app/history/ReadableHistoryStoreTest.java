// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableHistoryStoreTest {
    @Mock
    private ReadableHistoryStore subject;

    @Test
    void onlyReadyToAdoptIfNextConstructionIsCompleteAndMatching() {
        final var rosterHash = Bytes.wrap("RH");
        doCallRealMethod().when(subject).isReadyToAdopt(rosterHash);

        given(subject.getNextConstruction()).willReturn(HistoryProofConstruction.DEFAULT);
        assertFalse(subject.isReadyToAdopt(rosterHash));

        given(subject.getNextConstruction())
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetRosterHash(rosterHash)
                        .build());
        assertFalse(subject.isReadyToAdopt(rosterHash));

        given(subject.getNextConstruction())
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetRosterHash(rosterHash)
                        .targetProof(HistoryProof.DEFAULT)
                        .build());
        assertTrue(subject.isReadyToAdopt(rosterHash));
    }
}
