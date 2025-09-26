// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import org.hiero.consensus.roster.RosterStateId;

public enum OtterStateId {

    // Reserved ids
    PLATFORM_STATE_STATE_ID(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID), // 26
    ROSTER_STATE_STATE_ID(RosterStateId.ROSTER_STATE_STATE_ID), // 27
    ROSTERS_STATE_ID(RosterStateId.ROSTERS_STATE_ID), // 28

    CONSISTENCY_SINGLETON_STATE_ID(0);

    private final int id;

    OtterStateId(final int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
