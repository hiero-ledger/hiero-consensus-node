// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * Where a finding is routed. Only {@link #ASSERT} reaches the drift report.
 */
public enum Lane {
    /** Certain drift with evidence — appears in the report. */
    ASSERT,
    /** Unverifiable — appears only in the quiet log. */
    QUIET_LOG,
    /** Code exists that the KB does not document — separate coverage lane, out of the drift report. */
    COVERAGE_GAP,
    /** Symbol resolves but a cited line moved — a deterministic diff proposal, never auto-applied. */
    AUTO_FIX
}
