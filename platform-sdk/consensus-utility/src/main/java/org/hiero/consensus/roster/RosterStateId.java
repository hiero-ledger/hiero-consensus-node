// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;

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
    public static final String ROSTER_STATE_STATE_LABEL = computeLabel(SERVICE_NAME, ROSTER_STATE_KEY);

    /** Rosters state. */
    public static final String ROSTERS_KEY = "ROSTERS";

    public static final int ROSTERS_STATE_ID = StateKey.KeyOneOfType.ROSTERSERVICE_I_ROSTERS.protoOrdinal();
    public static final String ROSTERS_STATE_LABEL = computeLabel(SERVICE_NAME, ROSTERS_KEY);
}
