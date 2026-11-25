// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.monitor.uptime;

import org.hiero.consensus.model.event.EventOrigin;

/**
 * The self-event situation observed in a consensus round.
 *
 * <p>Only {@link EventOrigin#RUNTIME} self events demonstrate event-creation liveness in the
 * current runtime; self events reaching consensus via replay from local storage
 * ({@link EventOrigin#STORAGE}) or via gossip ({@link EventOrigin#GOSSIP}) do not.
 */
public enum SelfEventRoundSignal {
    /** The round contains no new self event reaching consensus. */
    NONE,
    /**
     * The round contains one or more new self events reaching consensus, but none with origin
     * {@link EventOrigin#RUNTIME}.
     */
    NON_RUNTIME_ONLY,
    /**
     * The round contains at least one new self event reaching consensus with origin
     * {@link EventOrigin#RUNTIME}.
     */
    RUNTIME_PRESENT
}
