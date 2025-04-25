// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

/**
 * A class with constants identifying Roster entities in state.
 */
public final class RosterStateId {
    private RosterStateId() {}

    /** The name of a service that owns Roster entities in state. */
    public static final String NAME = "RosterService";
    /** The name of the RosterMap. */
    public static final String ROSTER_KEY = "ROSTERS";
    /** The name of the RosterState. */
    public static final String ROSTER_STATES_KEY = "ROSTER_STATE";
}
