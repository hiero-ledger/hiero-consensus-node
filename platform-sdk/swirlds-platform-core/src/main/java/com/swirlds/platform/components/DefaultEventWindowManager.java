// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The default implementation of the {@link EventWindowManager} interface.
 */
public class DefaultEventWindowManager implements EventWindowManager {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow extractEventWindow(@NonNull final ConsensusRound round) {
        return round.getEventWindow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow updateEventWindow(@NonNull final EventWindow eventWindow) {
        return eventWindow;
    }
}
