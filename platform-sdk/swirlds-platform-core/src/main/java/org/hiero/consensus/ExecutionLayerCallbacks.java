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

    void onPlatformStatusChange(final PlatformStatus status);

    void onSealConsensusRound(final Round consensusRound);
}
