// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * Human-owned disposition of a finding, carried in the version-controlled baseline. A finding whose
 * identity re-appears after being {@link #DISMISSED} is suppressed from the report; because identity
 * is keyed on the KB's claim, changing the claim mints a new identity and re-surfaces it as {@link #NEW}.
 */
public enum Triage {
    /** Not yet triaged by a curator — the default disposition. */
    NEW,
    /** The finding has been reviewed and accepted as real drift. */
    ACCEPTED,
    /** The finding has been reviewed and dismissed; suppressed from the report until identity changes. */
    DISMISSED,
    /** The finding has been reviewed and deferred to a later time. */
    DEFERRED;

    /**
     * Parses a triage value from its wire string, defaulting to {@link #NEW} for {@code null} or
     * unrecognized input.
     *
     * @param s the wire string (case-insensitive), or {@code null}.
     * @return the matching triage, or {@link #NEW} if none matches.
     */
    public static Triage fromString(final String s) {
        if (s == null) {
            return NEW;
        }
        return switch (s.trim().toLowerCase()) {
            case "accepted" -> ACCEPTED;
            case "dismissed" -> DISMISSED;
            case "deferred" -> DEFERRED;
            default -> NEW;
        };
    }

    /**
     * Returns the lower-case wire form of this triage, as written to the baseline.
     *
     * @return the wire string.
     */
    public String wire() {
        return name().toLowerCase();
    }
}
