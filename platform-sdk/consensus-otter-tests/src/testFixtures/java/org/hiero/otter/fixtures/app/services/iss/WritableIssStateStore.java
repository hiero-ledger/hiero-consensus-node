// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.iss;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.app.state.OtterStateId.ISS_SINGLETON_STATE_ID;

import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.model.IssState;

/**
 * A writable store for the {@link IssService}.
 */
@SuppressWarnings("UnusedReturnValue")
public class WritableIssStateStore {

    private final WritableSingletonState<IssState> singletonState;

    /**
     * Constructs a new {@code WritableIssStore} instance.
     *
     * @param writableStates the writable states used to modify the ISS state
     */
    public WritableIssStateStore(@NonNull final WritableStates writableStates) {
        singletonState = writableStates.getSingleton(ISS_SINGLETON_STATE_ID.id());
    }

    /**
     * Sets the state value to the given value.
     *
     * @param value the value to set
     * @return this store for chaining
     */
    @NonNull
    public WritableIssStateStore setStateValue(final long value) {
        final IssState issState = requireNonNull(singletonState.get());
        singletonState.put(issState.copyBuilder().issState(value).build());

        return this;
    }
}
