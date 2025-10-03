// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.iss;

import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.model.ConsistencyState;
import org.hiero.otter.fixtures.app.model.IssState;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyService;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.NonCryptographicHashing.hash64;
import static org.hiero.otter.fixtures.app.state.OtterStateId.CONSISTENCY_SINGLETON_STATE_ID;

/**
 * A writable store for the {@link ConsistencyService}.
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
        singletonState = writableStates.getSingleton(CONSISTENCY_SINGLETON_STATE_ID.id());
    }

    /**
     * Updates the state value by the given value.
     *
     * @param value the value to increase the state by
     * @return this store for chaining
     */
    @NonNull
    public WritableIssStateStore increaseStateValue(final long value) {
        final IssState issState = requireNonNull(singletonState.get());

        final long stateValue = issState.issState();

        singletonState.put(
                issState.copyBuilder().issState(stateValue + value).build());

        return this;
    }

}
