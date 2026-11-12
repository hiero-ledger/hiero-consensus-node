// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;

/** Completed paired constructions for testing the real handoff coordinator at its production call sites. */
public record PreparedTssHandoff(
        Bytes rosterHash, HintsConstruction active, HintsConstruction next, HistoryProofConstruction history) {
    public PreparedTssHandoff(final Bytes rosterHash) {
        this(
                rosterHash,
                hints(1L, rosterHash, Bytes.wrap("old-key")),
                hints(2L, rosterHash, Bytes.wrap("new-key")),
                history(rosterHash));
    }

    public void stub(final ReadableHintsStore store) {
        lenient().when(store.getActiveConstruction()).thenReturn(active);
        lenient().when(store.getNextConstruction()).thenReturn(next);
        lenient()
                .when(store.getCrsState())
                .thenReturn(CRSState.newBuilder().numParties(8).build());
        lenient().when(store.isReadyToAdopt(rosterHash)).thenReturn(true);
    }

    public void stub(final ReadableHistoryStore store) {
        lenient().when(store.getLedgerId()).thenReturn(Bytes.wrap("ledger"));
        lenient()
                .when(store.getActiveConstruction())
                .thenReturn(
                        HistoryProofConstruction.newBuilder().constructionId(1L).build());
        lenient().when(store.getNextConstruction()).thenReturn(history);
        lenient().when(store.isReadyToAdopt(rosterHash)).thenReturn(true);
    }

    private static HintsConstruction hints(final long id, final Bytes hash, final Bytes key) {
        return HintsConstruction.newBuilder()
                .constructionId(id)
                .targetRosterHash(hash)
                .numParties(8)
                .hintsScheme(new HintsScheme(new PreprocessedKeys(Bytes.wrap("aggregation-key"), key), List.of()))
                .build();
    }

    private static HistoryProofConstruction history(final Bytes hash) {
        final var provenance = HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(1L)
                .targetHintsConstructionId(2L)
                .historyConstructionId(2L)
                .sourceVerificationKey(Bytes.wrap("old-key"))
                .targetVerificationKey(Bytes.wrap("new-key"))
                .signature(Bytes.wrap("certificate"))
                .build();
        return HistoryProofConstruction.newBuilder()
                .constructionId(2L)
                .targetRosterHash(hash)
                .keyProvenance(provenance)
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(History.newBuilder().metadata(provenance.targetVerificationKey()))
                        .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("proof")))
                        .uncompressedWrapsProof(Bytes.wrap("uncompressed"))
                        .keyProvenanceSignature(provenance.signature()))
                .build();
    }
}
