// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

public enum SyncPhase {
    IDLE,
    EXCHANGING_WINDOWS,
    EXCHANGING_TIPS,
    EXCHANGING_EVENTS,
    RECEIVING_EVENTS,
    SENDING_EVENTS,
    OTHER_FALLEN_BEHIND,
    SELF_FALLEN_BEHIND
}
