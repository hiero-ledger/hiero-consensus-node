// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the near-name similarity scoring, in particular the guard that keeps long, unrelated
 * identifiers from crossing the threshold on incidental character overlap alone.
 */
class NearNameMatcherTest {

    @Test
    void unrelatedLongIdentifiersDoNotCrossTheThreshold() {
        // Real-world false positives: fuzzy hints once offered for the deleted
        // NonDeterministicGeneration.java. None shares its head token, and none is a near-typo.
        assertThat(NearNameMatcher.similarity("NonDeterministicGeneration", "DeterministicThrottle"))
                .isLessThan(0.5);
        assertThat(NearNameMatcher.similarity("NonDeterministicGeneration", "DelegatingOperation"))
                .isLessThan(0.5);
        assertThat(NearNameMatcher.similarity("NonDeterministicGeneration", "OrderedInIsolation"))
                .isLessThan(0.5);
    }

    @Test
    void typosAndTokenOverlapsStillScore() {
        // A near-typo (heads differ, but the edit similarity is near-certain).
        assertThat(NearNameMatcher.similarity("RUL-001-fixtur", "RUL-001-fixture"))
                .isGreaterThanOrEqualTo(0.5);
        // Full-token containment (the boost path).
        assertThat(NearNameMatcher.similarity("pces", "restart-and-pces")).isGreaterThanOrEqualTo(0.5);
        assertThat(NearNameMatcher.similarity("signed-state", "signed-state-management"))
                .isGreaterThanOrEqualTo(0.5);
        // Same head token with a changed qualifier.
        assertThat(NearNameMatcher.similarity("EventCreater", "EventCreator")).isGreaterThanOrEqualTo(0.5);
    }
}
