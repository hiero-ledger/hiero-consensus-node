// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.nexus.DefaultLatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.signed.DefaultStateSignatureCollector;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * A StateSignatureCollector that is used for unit testing. In the future, these unit tests should become small
 * integration tests that test multiple components, this class should be removed once we have achieved that.
 */
public class StateSignatureCollectorTester extends DefaultStateSignatureCollector {
    private final LatestCompleteStateNexus latestSignedState;
    private final StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer;
    private final StateLacksSignaturesConsumer stateLacksSignaturesConsumer;

    private StateSignatureCollectorTester(
            @NonNull final PlatformContext platformContext,
            @NonNull final SignedStateMetrics signedStateMetrics,
            @NonNull final LatestCompleteStateNexus latestSignedState,
            @NonNull final StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer,
            @NonNull final StateLacksSignaturesConsumer stateLacksSignaturesConsumer) {
        super(platformContext, signedStateMetrics);
        this.latestSignedState = latestSignedState;
        this.stateHasEnoughSignaturesConsumer = stateHasEnoughSignaturesConsumer;
        this.stateLacksSignaturesConsumer = stateLacksSignaturesConsumer;
    }

    public static StateSignatureCollectorTester create(
            @NonNull final PlatformContext platformContext,
            @NonNull final SignedStateMetrics signedStateMetrics,
            @NonNull final StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer,
            @NonNull final StateLacksSignaturesConsumer stateLacksSignaturesConsumer) {
        final LatestCompleteStateNexus latestSignedState = new DefaultLatestCompleteStateNexus(platformContext);
        return new StateSignatureCollectorTester(
                platformContext,
                signedStateMetrics,
                latestSignedState,
                stateHasEnoughSignaturesConsumer,
                stateLacksSignaturesConsumer);
    }

    @Override
    public List<ReservedSignedState> addReservedState(@NonNull final ReservedSignedState reservedSignedState) {
        final EventWindow window = new EventWindow(
                reservedSignedState.get().getRound(),
                1 /* ignored by this test */,
                1 /* ignored by this test */,
                AncientMode.GENERATION_THRESHOLD /* ignored by this test*/);

        latestSignedState.updateEventWindow(window);

        return processStates(super.addReservedState(reservedSignedState));
    }

    @Override
    public List<ReservedSignedState> handlePreconsensusSignatures(
            @NonNull final Queue<ScopedSystemTransaction<StateSignatureTransaction>> transactions) {
        return processStates(super.handlePreconsensusSignatures(transactions));
    }

    public void handlePreconsensusSignatureTransaction(
            @NonNull final NodeId signerId, @NonNull final StateSignatureTransaction signatureTransaction) {
        final Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions =
                new ConcurrentLinkedQueue<>();
        systemTransactions.add(new ScopedSystemTransaction<>(signerId, null, signatureTransaction));
        handlePreconsensusSignatures(systemTransactions);
    }

    @Override
    public List<ReservedSignedState> handlePostconsensusSignatures(
            @NonNull final Queue<ScopedSystemTransaction<StateSignatureTransaction>> transactions) {
        return processStates(super.handlePostconsensusSignatures(transactions));
    }

    public void handlePostconsensusSignatureTransaction(
            @NonNull final NodeId signerId, @NonNull final StateSignatureTransaction transaction) {
        final Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions =
                new ConcurrentLinkedQueue<>();
        systemTransactions.add(new ScopedSystemTransaction<>(signerId, null, transaction));
        handlePostconsensusSignatures(systemTransactions);
    }

    private List<ReservedSignedState> processStates(@Nullable final List<ReservedSignedState> states) {
        for (final ReservedSignedState state : Optional.ofNullable(states).orElse(List.of())) {
            try (state) {
                processState(state);
            }
        }
        return states;
    }

    private void processState(@NonNull final ReservedSignedState rs) {
        if (rs.get().isComplete()) {
            latestSignedState.setStateIfNewer(rs.getAndReserve("LatestCompleteStateNexus.setState"));
            stateHasEnoughSignaturesConsumer.stateHasEnoughSignatures(rs.get());
        } else {
            stateLacksSignaturesConsumer.stateLacksSignatures(rs.get());
        }
    }

    /**
     * Get the last complete signed state
     *
     * @param reason a short description of why this SignedState is being reserved. Each location where a SignedState is
     *               reserved should attempt to use a unique reason, as this makes debugging reservation bugs easier.
     * @return the latest complete signed state, or a null if there are no recent states that are complete
     */
    @Nullable
    public ReservedSignedState getLatestSignedState(@NonNull final String reason) {
        return latestSignedState.getState(reason);
    }

    public long getLastCompleteRound() {
        return latestSignedState.getRound();
    }
}
