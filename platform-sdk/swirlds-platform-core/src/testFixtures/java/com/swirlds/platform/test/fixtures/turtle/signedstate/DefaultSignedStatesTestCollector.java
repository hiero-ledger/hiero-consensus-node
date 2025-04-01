// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * A container for collecting reserved signed states using List.
 */
public class DefaultSignedStatesTestCollector implements SignedStatesTestCollector {

    final Map<MerkleNodeState, ReservedSignedState> collectedSignedStates = new HashMap<>();
    final NodeId selfNodeId;

    public DefaultSignedStatesTestCollector(final NodeId selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void interceptReservedSignedState(@NonNull final ReservedSignedState signedState) {
        try (signedState) {
            assertThat(collectedSignedStates)
                    .withFailMessage(String.format(
                            "SignedState with root %s has been already produced by node %d",
                            signedState.get().getState().getRoot(), selfNodeId.id()))
                    .doesNotContainKey(signedState.get().getState());
            collectedSignedStates.put(signedState.get().getState(), signedState);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear(@NonNull final Set<MerkleNodeState> merkleRoots) {
        for (final MerkleNodeState merkleRoot : merkleRoots) {
            final ReservedSignedState removedState = collectedSignedStates.remove(merkleRoot);
            if (removedState != null) {
                removedState.close();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<MerkleNodeState, ReservedSignedState> getCollectedSignedStates() {
        return collectedSignedStates;
    }

    @Override
    public List<ReservedSignedState> getFilteredSignedStates(@NonNull Set<MerkleNodeState> merkleStates) {
        return collectedSignedStates.entrySet().stream()
                .filter(s -> merkleStates.contains(s.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }
}
