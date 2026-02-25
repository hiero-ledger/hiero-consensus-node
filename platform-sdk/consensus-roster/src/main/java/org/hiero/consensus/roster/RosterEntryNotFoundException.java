// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An exception thrown when a RosterEntry cannot be found, e.g. when searching by NodeId.
 */
public class RosterEntryNotFoundException extends RuntimeException {
    /**
     * A default constructor.
     * @param message a message
     */
    public RosterEntryNotFoundException(@NonNull final String message) {
        super(message);
    }
}
