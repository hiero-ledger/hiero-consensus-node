// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;

/** Completed paired constructions for testing the real handoff coordinator at its production call sites. */
public record PreparedTssHandoff(
        Bytes rosterHash, HintsConstruction active, HintsConstruction next, HistoryProofConstruction history) {
    public PreparedTssHandoff(final Bytes rosterHash) {
        this(rosterHash, 1L);
    }

    public PreparedTssHandoff(final Bytes rosterHash, final long nodeWeight) {
        this(
                rosterHash,
                hints(1L, rosterHash, Bytes.wrap("old-key"), nodeWeight),
                hints(2L, rosterHash, Bytes.wrap("new-key"), nodeWeight),
                history(rosterHash, nodeWeight));
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

    private static HintsConstruction hints(final long id, final Bytes hash, final Bytes key, final long nodeWeight) {
        return HintsConstruction.newBuilder()
                .constructionId(id)
                .targetRosterHash(hash)
                .numParties(8)
                .hintsScheme(new HintsScheme(
                        new PreprocessedKeys(Bytes.wrap("aggregation-key"), key),
                        List.of(new NodePartyId(0L, 0, nodeWeight))))
                .build();
    }

    private static HistoryProofConstruction history(final Bytes hash, final long nodeWeight) {
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
                        .targetNodeWeights(new ProofNodeWeight(0L, nodeWeight))
                        .targetHistory(History.newBuilder().metadata(provenance.targetVerificationKey()))
                        .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("proof")))
                        .uncompressedWrapsProof(Bytes.wrap("uncompressed"))
                        .keyProvenanceSignature(provenance.signature()))
                .build();
    }
}
