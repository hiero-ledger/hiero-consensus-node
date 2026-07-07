// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.worklist;

import java.util.List;

/**
 * One topic's semantic-review status. The semantic (Tier-3) pass processes only entries whose status
 * is {@link Status#REVIEW} or {@link Status#UNKNOWN}.
 *
 * @param entryKey     the topic entry key.
 * @param entryPath    the topic's repo-relative path.
 * @param lastReviewed the topic's {@code last_reviewed} value (may be null or non-date).
 * @param status       whether anchored source changed since the review.
 * @param changedPaths anchored source paths whose last commit post-dates {@code lastReviewed}, sorted.
 */
public record WorklistEntry(
        String entryKey, String entryPath, String lastReviewed, Status status, List<String> changedPaths) {

    /** Freshness of a topic relative to the code it anchors. */
    public enum Status {
        /** Anchored source changed since {@code last_reviewed}, or the marker is missing/non-date. */
        REVIEW,
        /** No anchored source changed since {@code last_reviewed}. */
        FRESH,
        /** Freshness could not be determined (git unavailable, no resolvable anchors). */
        UNKNOWN
    }
}
