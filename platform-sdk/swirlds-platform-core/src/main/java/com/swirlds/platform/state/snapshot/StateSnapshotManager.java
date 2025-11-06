// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.state.StateSavingResult;

/**
 * This class is responsible for managing the signed state writing pipeline.
 */
public interface StateSnapshotManager {

    /**
     * Method to be called when a state needs to be written to disk in-band. An "in-band" write is part of normal
     * platform operations, whereas an out-of-band write is triggered due to a fault, or for debug purposes.
     * <p>
     * This method shouldn't be called if the state was written out-of-band.
     *
     * @param reservedSignedState the state to be written to disk. it is expected that the state is reserved prior to
     *                            this method call and this method will release the reservation when it is done
     * @return the result of the state saving operation, or null if the state was not saved
     */
    @Nullable
    StateSavingResult saveStateTask(@NonNull ReservedSignedState reservedSignedState);

    /**
     * Method to be called when a state needs to be written to disk out-of-band. An "in-band" write is part of normal
     * platform operations, whereas an out-of-band write is triggered due to a fault, or for debug purposes.
     *
     * @param request a request to dump a state to disk. it is expected that the state inside the request is reserved
     *                prior to this method call and this method will release the reservation when it is done
     */
    void dumpStateTask(@NonNull StateDumpRequest request);

    /**
     * Convert a {@link StateSavingResult} to a {@link StateWriteToDiskCompleteNotification}.
     *
     * @param result the result of the state saving operation
     * @return the notification
     */
    @NonNull
    default StateWriteToDiskCompleteNotification toNotification(@NonNull final StateSavingResult result) {
        return new StateWriteToDiskCompleteNotification(
                result.round(), result.consensusTimestamp(), result.freezeState());
    }

    /**
     * Extract the oldest minimum birth round on disk from a {@link StateSavingResult}.
     *
     * @param result the result of the state saving operation
     * @return the oldest minimum birth round on disk
     */
    @NonNull
    default Long extractOldestMinimumBirthRoundOnDisk(@NonNull final StateSavingResult result) {
        return result.oldestMinimumBirthRoundOnDisk();
    }
}
