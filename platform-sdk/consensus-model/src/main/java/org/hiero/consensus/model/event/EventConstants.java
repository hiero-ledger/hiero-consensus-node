// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

public final class EventConstants {
    /**
     * Private constructor so that this class is never instantiated
     */
    private EventConstants() {}

    /** the smallest round an event can belong to */
    public static final long MINIMUM_ROUND_CREATED = 1;
    /** the round number to represent that the birth round is undefined */
    public static final long BIRTH_ROUND_UNDEFINED = -1;
    /** represent either a birth round or a generation which is undefined */
    public static final long ANCIENT_THRESHOLD_UNDEFINED = -1;
    /** the minimum generation value an event can have. */
    public static final long FIRST_GENERATION = 0;
    /**
     * Represents an undefined sequence number. This constant is used as a placeholder to
     * indicate that a specific sequence number has not yet been assigned.
     * <p>
     * The value of {@code SEQUENCE_NUMBER_UNDEFINED} is defined as {@code -1}. This value is chosen because sequence
     * numbers are non-negative, making {@code -1} a clear and unambiguous indicator of the unassigned state.
     */
    public static final long SEQUENCE_NUMBER_UNDEFINED = -1;
}
