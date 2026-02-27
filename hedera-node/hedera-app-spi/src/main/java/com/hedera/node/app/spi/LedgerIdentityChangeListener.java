// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A listener notified whenever the ledger's identity changes (roster adoption or ledger ID change),
 * but only when both the active roster and ledger ID are available.
 *
 * <p>Any {@link RpcService} implementing this interface is automatically discovered and called
 * by the framework when the ledger identity changes.
 */
public interface LedgerIdentityChangeListener {
    /**
     * Called when the ledger identity changes and both the active roster and ledger ID are available.
     *
     * @param activeRoster the current active roster
     * @param ledgerId the current ledger ID
     * @param consensusTime the consensus time of the change
     * @param state the current state for direct writes
     */
    void onLedgerIdentityChanged(
            @NonNull Roster activeRoster, @NonNull Bytes ledgerId, @NonNull Instant consensusTime, @NonNull State state);
}
