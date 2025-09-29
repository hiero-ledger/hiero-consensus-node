// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import org.hiero.consensus.roster.RosterStateId;

/**
 * This enum defines the state ids used by the Otter application.
 */
public enum OtterStateId {

    // Reserved ids
    /** Platform state id, used by the platform service. */
    PLATFORM_STATE_STATE_ID(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID), // 26

    /** Roster state ids, used by the roster service. */
    ROSTER_STATE_STATE_ID(RosterStateId.ROSTER_STATE_STATE_ID), // 27

    /** Rosters state ids, used by the roster service. */
    ROSTERS_STATE_ID(RosterStateId.ROSTERS_STATE_ID), // 28

    /** Consistency state id, used by the consistency service. */
    CONSISTENCY_SINGLETON_STATE_ID(1);

    private final int id;

    OtterStateId(final int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
