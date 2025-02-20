// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The gateway for disseminating {@link EventWindow} instances to the rest of the platform.
 */
public interface EventWindowManager {

    /**
     * Extracts the {@link EventWindow} from the given {@link ConsensusRound}.
     *
     * @param round the {@link ConsensusRound}
     * @return the {@link EventWindow}
     */
    @InputWireLabel("consensus round")
    EventWindow extractEventWindow(@NonNull final ConsensusRound round);

    /**
     * Set the {@link EventWindow}.
     *
     * @param eventWindow the {@link EventWindow}
     * @return the {@link EventWindow} that was set.
     */
    @InputWireLabel("override event window")
    EventWindow updateEventWindow(@NonNull final EventWindow eventWindow);
}
