// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.states;

/**
 * An abstract {@link NegotiationState} that stores a variable with the latest transition description
 */
public abstract class NegotiationStateWithDescription implements NegotiationState {
    private String description = "NO TRANSITION";

    /**
     * Set the description that is returned by {@link #getLastTransitionDescription()}
     *
     * @param description
     * 		the description
     */
    public void setDescription(final String description) {
        this.description = description;
    }

    @Override
    public String getLastTransitionDescription() {
        return description;
    }
}
