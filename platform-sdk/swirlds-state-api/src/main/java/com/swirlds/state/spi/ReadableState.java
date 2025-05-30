// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface ReadableState {

    /**
     * Gets the name of the service to which this state belongs.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The name of the service. This will never be null, and will always be the same value for an
     *     instance of {@link ReadableState}.
     */
    @NonNull
    String getServiceName();

    /**
     * Gets the "state key" that uniquely identifies this {@link ReadableState} within the
     * {@link Schema} which are scoped to the service implementation. The key is therefore not globally
     * unique, only unique within the service implementation itself.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The state key. This will never be null, and will always be the same value for an
     *     instance of {@link ReadableState}.
     */
    @NonNull
    String getStateKey();
}
