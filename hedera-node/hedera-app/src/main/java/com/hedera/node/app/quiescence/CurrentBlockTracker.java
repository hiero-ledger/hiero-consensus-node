// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;

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
