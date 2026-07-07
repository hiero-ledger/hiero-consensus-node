// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.monitoring;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.EventWindow;

public enum FallenBehindStatus {
    NONE_FALLEN_BEHIND,
    SELF_FALLEN_BEHIND,
    OTHER_FALLEN_BEHIND;

    /**
     * Compute the fallen behind status between ourselves and a peer.
     *
     * @param self  our event window
     * @param other the peer's event window
     * @return the status
     */
    @NonNull
    public static FallenBehindStatus getStatus(@NonNull final EventWindow self, @NonNull final EventWindow other) {
        if (other.ancientThreshold() < self.expiredThreshold()) {
            return OTHER_FALLEN_BEHIND;
        }
        if (self.ancientThreshold() < other.expiredThreshold()) {
            return SELF_FALLEN_BEHIND;
        }
        return NONE_FALLEN_BEHIND;
    }
}
