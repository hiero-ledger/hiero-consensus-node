// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import com.swirlds.state.lifecycle.Schema;

public interface ReadableState {

    /**
     * Gets the "state key" that uniquely identifies this {@link ReadableState} within the
     * {@link Schema} which are scoped to the service implementation. The key is therefore not globally
     * unique, only unique within the service implementation itself.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The state ID. This will always be the same value for an instance of {@link ReadableState}.
     */
    int getStateId();
}
