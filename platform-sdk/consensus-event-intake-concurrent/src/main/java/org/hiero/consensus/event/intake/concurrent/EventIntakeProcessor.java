// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.roster.RosterHistory;

/**
 * A single concurrent component that replaces the 4-stage pre-orphan-buffer pipeline:
 * hash, validate, deduplicate, and verify signature.
 *
 * <p>Each event flows through all stages on the same thread within a single task execution,
 * eliminating 3 inter-stage queues and 3 thread pool dispatches per event.
 */
public interface EventIntakeProcessor {

    /**
     * Process an unhashed event received from gossip. Hashes the event, then validates,
     * deduplicates, and verifies its signature.
     *
     * @param event the unhashed event
     * @return the event if it passes all checks, or null if it should be discarded
     */
    @Nullable
    @InputWireLabel("unhashed events")
    PlatformEvent processUnhashedEvent(@NonNull PlatformEvent event);

    /**
     * Process a pre-hashed event (e.g. self-created). Validates, deduplicates, and verifies
     * its signature (skipped for {@code RUNTIME} origin events).
     *
     * @param event the pre-hashed event
     * @return the event if it passes all checks, or null if it should be discarded
     */
    @Nullable
    @InputWireLabel("pre-hashed events")
    PlatformEvent processHashedEvent(@NonNull PlatformEvent event);

    /**
     * Set the event window that defines the minimum threshold required for an event to be non-ancient.
     *
     * @param eventWindow the event window
     */
    @InputWireLabel("event window")
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Update the roster history used for signature verification.
     *
     * @param rosterHistory the roster history read from state
     */
    @InputWireLabel("roster history")
    void updateRosterHistory(@NonNull RosterHistory rosterHistory);

    /**
     * Clear all internal state (deduplication tracking, verifier cache, etc.).
     */
    void clear();
}
