// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.adapter;

import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static java.util.Objects.requireNonNull;

import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.StaleEventConsumer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.ExecutionLayerCallbacks;
import org.hiero.consensus.main.model.Event;
import org.hiero.consensus.main.model.Round;
import org.hiero.consensus.main.model.TimestampedTransaction;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.state.nexus.SignedStateNexus;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

public class AdapterCallbacks implements ExecutionLayerCallbacks {

    @NonNull
    private final ConsensusStateEventHandler consensusStateEventHandler;

    @NonNull
    private final ExecutionLayer executionLayer;

    /**
     * A source to get the latest immutable state
     */
    @NonNull
    private final SignedStateNexus latestImmutableStateNexus;

    @Nullable
    private final StaleEventConsumer staleEventConsumer;

    @NonNull
    private final ComponentWiring<AppNotifier, Void> notifierWiring;

    @NonNull
    private final StateModule stateModule;

    @NonNull
    private final TransactionHandlingModule transactionHandlingModule;

    public AdapterCallbacks(
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final ExecutionLayer executionLayer,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @Nullable final StaleEventConsumer staleEventConsumer,
            @NonNull final ComponentWiring<AppNotifier, Void> notifierWiring,
            @NonNull final StateModule stateModule,
            @NonNull final TransactionHandlingModule transactionHandlingModule) {
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.executionLayer = requireNonNull(executionLayer);
        this.latestImmutableStateNexus = requireNonNull(latestImmutableStateNexus);
        this.staleEventConsumer = staleEventConsumer;
        this.notifierWiring = requireNonNull(notifierWiring);
        this.stateModule = requireNonNull(stateModule);
        this.transactionHandlingModule = requireNonNull(transactionHandlingModule);
    }

    @Override
    public void onBehind() {}

    @Override
    public List<TimestampedTransaction> getTransactionsForNewEvent() {
        return executionLayer.getTransactionsForEvent();
    }

    @Override
    public void onStaleEvent(@NonNull final Event event) {
        staleEventConsumer.processStaleEvent(event);
    }

    @Override
    public void onPreHandle(@NonNull final Event event) {
        transactionHandlingModule.preHandleEventInputWire().put(event);
    }

    @Override
    public void onRound(@NonNull final Round round) {
        stateModule.consensusRoundInputWire().inject(round);
    }

    @Override
    public void onPlatformStatusChange(@NonNull final PlatformStatus status) {
        executionLayer.newPlatformStatus(status);
        notifierWiring.getInputWire(AppNotifier::sendPlatformStatusChangeNotification).inject(status);
        stateModule.platformStatusInputWire().inject(status);
    }

    @Override
    public void onSealConsensusRound(Round consensusRound) {}

    @Override
    public void onUnhealthySignal(@NonNull final Duration unhealthyDuration) {
        executionLayer.reportUnhealthyDuration(unhealthyDuration);
    }
}
