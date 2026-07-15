// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.worklist;

import java.util.List;

/**
 * One topic's semantic-review status. The semantic (Tier-3) pass processes only entries whose status
 * is {@link Status#REVIEW} or {@link Status#UNKNOWN}.
 *
 * @param entryKey            the topic entry key.
 * @param entryPath           the topic's repo-relative path.
 * @param lastReviewed        the topic's {@code last_reviewed} value (may be null or non-date).
 * @param status              whether anchored source changed since the review.
 * @param note                for {@link Status#UNKNOWN}, why freshness could not be determined (e.g.
 *                            {@code no anchored sources}, {@code git unavailable}); otherwise {@code null}.
 * @param changedPaths        anchored source paths whose last commit post-dates {@code lastReviewed}, sorted.
 * @param anchoredSourceCount how many distinct source files the topic anchors (full or abbreviated,
 *                            resolved to concrete files). Zero means the doc carries no
 *                            mechanically-checkable code anchor — surfaced in the coverage lane.
 */
public record WorklistEntry(
        String entryKey,
        String entryPath,
        String lastReviewed,
        Status status,
        String note,
        List<String> changedPaths,
        int anchoredSourceCount) {

    /** Freshness of a topic relative to the code it anchors. */
    public enum Status {
        /** Anchored source changed since {@code last_reviewed}, or the marker is missing/non-date. */
        REVIEW,
        /** No anchored source changed since {@code last_reviewed}. */
        FRESH,
        /** Freshness could not be determined; the {@code note} names the reason. */
        UNKNOWN
    }
}
