// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import com.swirlds.platform.state.signed.ReservedSignedState;

/**
 * A simple DTO for passing information about a signed state to the GUI.
 *
 * @param round      the round number
 * @param numSigs    the number of signatures
 * @param isComplete whether the state is complete
 */
record GuiStateInfo(long round, int numSigs, boolean isComplete) {
    /**
     * Creates a new {@link GuiStateInfo} from the given {@link ReservedSignedState}.
     *
     * @param state the state
     * @return the new {@link GuiStateInfo}
     */
    static GuiStateInfo from(final ReservedSignedState state) {
        return new GuiStateInfo(
                state.get().getRound(),
                state.get().getSigSet().size(),
                state.get().isComplete());
    }
}
