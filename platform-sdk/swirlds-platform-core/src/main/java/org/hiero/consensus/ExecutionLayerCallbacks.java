// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import java.time.Duration;
import java.util.List;
import org.hiero.consensus.main.model.Event;
import org.hiero.consensus.main.model.Round;
import org.hiero.consensus.main.model.TimestampedTransaction;
import org.hiero.consensus.model.status.PlatformStatus;

public interface ExecutionLayerCallbacks {

    void onBehind();

    List<TimestampedTransaction> getTransactionsForNewEvent();

    void onStaleEvent(final Event event);

    void onPreHandle(final Event event);

    void onRound(final Round consensusRound);

    // TODO Consider adding a new "IDLE" status that kicks in if a new round has not been requested by the
    // Execution layer in a configurable amount of wall clock time. The consensus layer can only report CHECKING/ACTIVE
    // type status if it is creating events and reaching consensus, which it cannot do if the Execution layer
    // is not requesting new rounds.
    void onPlatformStatusChange(final PlatformStatus status);

    void onSealConsensusRound(final Round consensusRound);
}
