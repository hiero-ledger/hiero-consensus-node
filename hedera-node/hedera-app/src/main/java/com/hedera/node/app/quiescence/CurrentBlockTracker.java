// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Holds the tracker for the in-progress block.
 * <p>
 * <b>Not threadsafe</b>, should be used just from handle thread in places that affect block tracking and quiescence.
 */
@Singleton
public class CurrentBlockTracker {
    private QuiescenceBlockTracker tracker;

    @Inject
    public CurrentBlockTracker() {
        // Dagger2
    }

    /**
     * Switches the current block tracker, synchronously marking the previous block as just finished.
     * <p>
     * Only used by the {@link BlockRecordManagerImpl}, whose concept of finality does not extend to achieving a
     * TSS signature.
     * @param tracker the tracker to switch to
     * @return whether the previous block was being tracked
     */
    public boolean switchTracker(@NonNull final QuiescenceBlockTracker tracker) {
        requireNonNull(tracker);
        boolean finishedPrevious = false;
        if (this.tracker != null) {
            this.tracker.finishedHandlingTransactions();
            finishedPrevious = true;
        }
        this.tracker = tracker;
        return finishedPrevious;
    }

    /**
     * Sets the current block tracker.
     * @param tracker the tracker
     */
    public void setTracker(@NonNull final QuiescenceBlockTracker tracker) {
        this.tracker = requireNonNull(tracker);
    }

    public QuiescenceBlockTracker trackerOrThrow() {
        return requireNonNull(tracker);
    }
}
