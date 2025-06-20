// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A singleton class that provides access to the working {@link State}.
 */
@Singleton
public class WorkingStateAccessor {
    private State state = null;

    @Inject
    public WorkingStateAccessor() {
        // Default constructor
    }

    /**
     * Returns the working {@link State}.
     * @return the working {@link State}.
     */
    @Nullable
    public State getState() {
        return state;
    }

    /**
     * Sets the working {@link State}.
     * @param state the working {@link State}.
     */
    public void setState(State state) {
        requireNonNull(state);
        this.state = state;
    }
}
