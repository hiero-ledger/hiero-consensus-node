// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the near-name similarity scoring, in particular the guard that keeps long, unrelated
 * identifiers from crossing the threshold on incidental character overlap alone.
 */
class SuggestionsRendererTest {

    @Test
    void unrelatedLongIdentifiersDoNotCrossTheThreshold() {
        // Real-world false positives: fuzzy hints once offered for the deleted
        // NonDeterministicGeneration.java. None shares its head token, and none is a near-typo.
        assertThat(SuggestionsRenderer.similarity("NonDeterministicGeneration", "DeterministicThrottle"))
                .isLessThan(0.5);
        assertThat(SuggestionsRenderer.similarity("NonDeterministicGeneration", "DelegatingOperation"))
                .isLessThan(0.5);
        assertThat(SuggestionsRenderer.similarity("NonDeterministicGeneration", "OrderedInIsolation"))
                .isLessThan(0.5);
    }

    @Test
    void typosAndTokenOverlapsStillScore() {
        // A near-typo (heads differ, but the edit similarity is near-certain).
        assertThat(SuggestionsRenderer.similarity("RUL-001-fixtur", "RUL-001-fixture"))
                .isGreaterThanOrEqualTo(0.5);
        // Full-token containment (the boost path).
        assertThat(SuggestionsRenderer.similarity("pces", "restart-and-pces")).isGreaterThanOrEqualTo(0.5);
        assertThat(SuggestionsRenderer.similarity("signed-state", "signed-state-management"))
                .isGreaterThanOrEqualTo(0.5);
        // Same head token with a changed qualifier.
        assertThat(SuggestionsRenderer.similarity("EventCreater", "EventCreator"))
                .isGreaterThanOrEqualTo(0.5);
    }
}
