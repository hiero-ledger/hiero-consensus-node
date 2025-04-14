// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CountDownLatch;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;
import org.hiero.consensus.model.utility.interrupt.Uninterruptable;

/**
 * A request to dump a signed state to disk because of an unexpected occurrence.
 *
 * @param reservedSignedState the reserved signed state to be written to disk
 * @param finishedCallback    called after state writing is complete
 * @param waitForFinished     called to wait for state writing to complete
 */
public record StateDumpRequest(
        @NonNull ReservedSignedState reservedSignedState,
        @NonNull Runnable finishedCallback,
        @NonNull Runnable waitForFinished) {

    /**
     * Create a new state dump request.
     *
     * @param reservedSignedState the reserved signed state to be written to disk
     * @return the new state dump request
     */
    public static @NonNull StateDumpRequest create(@NonNull final ReservedSignedState reservedSignedState) {
        final CountDownLatch latch = new CountDownLatch(1);
        final InterruptableRunnable await = latch::await;
        return new StateDumpRequest(
                reservedSignedState,
                latch::countDown,
                () -> Uninterruptable.abortAndLogIfInterrupted(
                        await,
                        "interrupted while waiting for state dump to complete, state dump may not be completed"));
    }
}
