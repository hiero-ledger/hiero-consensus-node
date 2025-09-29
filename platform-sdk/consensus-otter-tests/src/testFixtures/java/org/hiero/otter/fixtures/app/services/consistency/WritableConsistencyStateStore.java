// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.NonCryptographicHashing.hash64;
import static org.hiero.otter.fixtures.app.state.OtterStateId.CONSISTENCY_SINGLETON_STATE_ID;

import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.model.ConsistencyState;

/**
 * A writable store for the {@link ConsistencyService}.
 */
@SuppressWarnings("UnusedReturnValue")
public class WritableConsistencyStateStore {

    private final WritableSingletonState<ConsistencyState> singletonState;

    /**
     * Constructs a new {@code WritableConsistencyStore} instance.
     *
     * @param writableStates the writable states used to modify the consistency state
     */
    public WritableConsistencyStateStore(@NonNull final WritableStates writableStates) {
        singletonState = writableStates.getSingleton(CONSISTENCY_SINGLETON_STATE_ID.id());
    }

    /**
     * Updates the running checksum by combining the current checksum and the given value.
     *
     * @param value the value to include in the running checksum
     * @return this store for chaining
     */
    @NonNull
    public WritableConsistencyStateStore accumulateRunningChecksum(final long value) {
        final ConsistencyState consistencyState = requireNonNull(singletonState.get());

        final long oldChecksum = consistencyState.runningChecksum();
        final long newChecksum = hash64(oldChecksum, value);

        singletonState.put(
                consistencyState.copyBuilder().runningChecksum(newChecksum).build());

        return this;
    }

    /**
     * Increases the number of rounds handled by 1.
     *
     * @return this store for chaining
     */
    @NonNull
    public WritableConsistencyStateStore increaseRoundsHandled() {
        final ConsistencyState consistencyState = requireNonNull(singletonState.get());

        final long roundsHandled = consistencyState.roundsHandled() + 1;

        singletonState.put(
                consistencyState.copyBuilder().roundsHandled(roundsHandled).build());

        return this;
    }
}
