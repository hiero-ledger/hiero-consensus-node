// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;

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
