package org.hiero.consensus;

import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.main.model.TimestampedTransaction;

public interface ExecutionLayerCallbacks {

    void onBehind();

    List<TimestampedTransaction> getTransactionForNewEvent();

    void onStaleEvent(final Event event);

    void onPreHandle(final Event event);

    void onRound(final Round consensusRound);

    void onPlatformStatusChange(final PlatformStatus status);

    void onSealConsensusRound(final Round consensusRound);

    void onUnhealthySignal(final Duration unhealthyDuration);
}
