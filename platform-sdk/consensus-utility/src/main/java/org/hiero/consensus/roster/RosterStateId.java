// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateValue;

/**
 * A class with constants identifying Roster entities in state.
 */
public final class RosterStateId {

    private RosterStateId() {}

    /** The name of a service that owns Roster entities in state. */
    public static final String SERVICE_NAME = "RosterService";

    /** Roster state state. */
    public static final String ROSTER_STATE_KEY = "ROSTER_STATE";

    public static final int ROSTER_STATE_STATE_ID = SingletonType.ROSTERSERVICE_I_ROSTER_STATE.protoOrdinal();

    /** Rosters state. */
    public static final String ROSTERS_KEY = "ROSTERS";

    public static final int ROSTERS_STATE_ID = StateValue.ValueOneOfType.ROSTERSERVICE_I_ROSTERS.protoOrdinal();
}
