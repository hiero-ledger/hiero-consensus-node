// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import java.util.Map;
import java.util.TreeMap;

/** Matching complete history generations for signing tests. */
final class HintsGenerationTestUtils {
    private HintsGenerationTestUtils() {}

    static HintsGenerationWeights weightsFor(final HintsConstruction construction) {
        return HintsGenerationWeights.from(construction, historyFor(construction));
    }

    static HistoryProofConstruction historyFor(final HintsConstruction construction) {
        final var weights = new TreeMap<Long, Long>();
        construction.hintsSchemeOrThrow().nodePartyIds().forEach(p -> weights.put(p.nodeId(), p.partyWeight()));
        return historyFor(construction, weights);
    }

    static HistoryProofConstruction historyFor(
            final HintsConstruction construction, final Map<Long, Long> completeWeights) {
        return HistoryProofConstruction.newBuilder()
                .targetRosterHash(construction.targetRosterHash())
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(History.newBuilder()
                                .metadata(construction
                                        .hintsSchemeOrThrow()
                                        .preprocessedKeysOrThrow()
                                        .verificationKey()))
                        .targetNodeWeights(completeWeights.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(e -> new ProofNodeWeight(e.getKey(), e.getValue()))
                                .toList()))
                .build();
    }
}
