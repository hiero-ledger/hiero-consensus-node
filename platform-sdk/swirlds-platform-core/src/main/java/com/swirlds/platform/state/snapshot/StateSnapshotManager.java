// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This class is responsible for managing the signed state writing pipeline.
 */
public interface StateSnapshotManager {

    /**
     * Method to be called when a state needs to be written to disk in-band. An "in-band" write is part of normal
     * platform operations, whereas an out-of-band write is triggered due to a fault, or for debug purposes.
     * <p>
     * This method shouldn't be called if the state was written out-of-band.
     * <p>
     * <b>Reservation contract:</b> The caller must reserve the state before calling this method.
     * This method takes ownership of the reservation and guarantees that it will be released
     * before returning, whether the operation succeeds, fails, or the state was already saved.
     * Note that the reservation may be released <em>during</em> execution (e.g., to unblock
     * asynchronous snapshot creation), so callers must not use the reserved state after this call.
     * <p>
     * <b>Sync vs async snapshot:</b> Depending on configuration and the state's save reason,
     * the snapshot may be created synchronously (blocking until the snapshot is fully written)
     * or asynchronously (the reservation is released early to allow the virtual pipeline to
     * flush the map copy, and this method blocks until the flush-triggered snapshot completes
     * or a configurable timeout expires). In both cases, the method does not return until the
     * snapshot operation has finished or failed.
     *
     * @param reservedSignedState the reserved state to be written to disk. Must be reserved by the caller;
     *                            this method takes ownership and will release the reservation
     * @return the result of the state saving operation, or null if the state was not saved
     *         (e.g., it was already saved to disk, or the write failed)
     */
    @Nullable
    StateSavingResult saveStateTask(@NonNull ReservedSignedState reservedSignedState);

    /**
     * Method to be called when a state needs to be written to disk out-of-band. An "in-band" write is part of normal
     * platform operations, whereas an out-of-band write is triggered due to a fault, or for debug purposes.
     * <p>
     * <b>Reservation contract:</b> The caller must reserve the state inside the request before calling this method.
     * This method takes ownership of the reservation and guarantees that it will be released
     * before returning, whether the operation succeeds or fails.
     * Note that the reservation may be released <em>during</em> execution (e.g., to unblock
     * asynchronous snapshot creation), so callers must not use the reserved state after this call.
     * <p>
     * <b>Sync vs async snapshot:</b> Depending on configuration and the state's save reason,
     * the snapshot may be created synchronously or asynchronously. See
     * {@link #saveStateTask(ReservedSignedState)} for details on the difference.
     *
     * @param request a request to dump a state to disk. The state inside the request must be reserved
     *                by the caller; this method takes ownership and will release the reservation
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
