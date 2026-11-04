// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.WrapsMessageDetails;
import com.hedera.hapi.node.state.history.WrapsMessageHistory;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableHistoryStoreTest {
    @Mock
    private ReadableHistoryStore subject;

    @Test
    void companionSurvivesPersistedMessageSerializationAndReplay() throws Exception {
        final var companion = HintsPartialSignatureTransactionBody.newBuilder()
                .constructionId(7)
                .message(Bytes.wrap("exact successor verification key"))
                .partialSignature(Bytes.wrap("source epoch partial signature"))
                .build();
        final var publication = new WrapsMessagePublication(
                10, Bytes.wrap("WRAPS rotation R1"), WrapsPhase.R1, Instant.ofEpochSecond(123, 456), companion);
        final var details = publication.asWrapsMessageDetails();
        final var serialized = WrapsMessageDetails.PROTOBUF.toBytes(details);
        final var restoredDetails = WrapsMessageDetails.PROTOBUF.parse(serialized);
        final var restored =
                WrapsMessagePublication.allFromHistory(10, new WrapsMessageHistory(List.of(restoredDetails)));

        assertEquals(List.of(publication), restored);
        assertEquals(companion, restored.getFirst().hintsPartialSignature());
    }

    @Test
    void legacyMessagesReplayWithoutManufacturingACompanion() throws Exception {
        final var publication =
                new WrapsMessagePublication(10, Bytes.wrap("WRAPS R2"), WrapsPhase.R2, Instant.ofEpochSecond(123));
        final var serialized = WrapsMessageDetails.PROTOBUF.toBytes(publication.asWrapsMessageDetails());
        final var restored = WrapsMessagePublication.allFromHistory(
                10, new WrapsMessageHistory(List.of(WrapsMessageDetails.PROTOBUF.parse(serialized))));

        assertEquals(List.of(publication), restored);
        assertNull(restored.getFirst().hintsPartialSignature());
    }

    @Test
    void onlyReadyToAdoptIfNextConstructionHasMatchingWrapsExtensibleProof() {
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
        assertFalse(subject.isReadyToAdopt(rosterHash));

        given(subject.getNextConstruction())
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetRosterHash(Bytes.wrap("OTHER"))
                        .targetProof(HistoryProof.newBuilder().uncompressedWrapsProof(Bytes.wrap("<proof>")))
                        .build());
        assertFalse(subject.isReadyToAdopt(rosterHash));

        given(subject.getNextConstruction())
                .willReturn(HistoryProofConstruction.newBuilder()
                        .targetRosterHash(rosterHash)
                        .targetProof(HistoryProof.newBuilder().uncompressedWrapsProof(Bytes.wrap("<proof>")))
                        .build());
        assertTrue(subject.isReadyToAdopt(rosterHash));
    }
}
