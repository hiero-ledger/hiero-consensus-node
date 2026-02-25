// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

/**
 * An exception thrown by the RosterRetriever.buildRoster() when a given AddressBook is invalid.
 */
public class InvalidAddressBookException extends RuntimeException {
    /**
     * A default constructor.
     * @param cause a cause
     */
    public InvalidAddressBookException(Exception cause) {
        super(cause);
    }
}
