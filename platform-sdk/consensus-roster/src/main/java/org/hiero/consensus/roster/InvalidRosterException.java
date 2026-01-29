// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

/**
 * An exception thrown by the RosterValidator when a given Roster is invalid.
 */
public class InvalidRosterException extends RuntimeException {
    /**
     * A default constructor.
     * @param message a message
     */
    public InvalidRosterException(String message) {
        super(message);
    }
}
