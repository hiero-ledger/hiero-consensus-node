// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * A container for collecting reserved signed states for each round.
 */
public class DefaultSignedStatesTestCollector implements SignedStatesTestCollector {

    final Map<Long, ReservedSignedState> collectedSignedStates = new HashMap<>();
    final NodeId selfNodeId;

    public DefaultSignedStatesTestCollector(@NonNull final NodeId selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void interceptReservedSignedState(@NonNull final ReservedSignedState signedState) {
        assertThat(collectedSignedStates)
                .withFailMessage(String.format(
                        "SignedState from round %s has been already produced by node %d",
                        signedState.get().getRound(), selfNodeId.id()))
                .doesNotContainKey(signedState.get().getRound());
        collectedSignedStates.put(signedState.get().getRound(), signedState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear(@NonNull final Set<Long> roundNumbers) {
        for (final Long roundNumber : roundNumbers) {
            final ReservedSignedState removedState = collectedSignedStates.remove(roundNumber);
            if (removedState != null) {
                removedState.close();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Map<Long, ReservedSignedState> getCollectedSignedStates() {
        return collectedSignedStates;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<ReservedSignedState> getFilteredSignedStates(@NonNull final Set<Long> roundNumbers) {
        return collectedSignedStates.entrySet().stream()
                .filter(s -> roundNumbers.contains(s.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }
}
