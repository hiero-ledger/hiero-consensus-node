// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

public final class EventConstants {
    /**
     * Private constructor so that this class is never instantiated
     */
    private EventConstants() {}

    /** the smallest round an event can belong to */
    public static final long MINIMUM_ROUND_CREATED = 1;
    /** represents a birth round number that is undefined */
    public static final long BIRTH_ROUND_UNDEFINED = -1;
    /** represents an ancient threshold that is undefined */
    public static final long ANCIENT_THRESHOLD_UNDEFINED = -1;
    /** the smallest sequence number an event can have */
    public static final long FIRST_SEQUENCE_NUMBER = 1;
    /** represents a sequence number that is undefined */
    public static final long SEQUENCE_NUMBER_UNDEFINED = -1;
}
