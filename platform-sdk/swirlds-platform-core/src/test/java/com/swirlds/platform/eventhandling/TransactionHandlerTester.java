// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateValueAccumulator;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.mockito.MockedStatic;

/**
 * A helper class for testing the {@link DefaultTransactionHandler}.
 */
public class TransactionHandlerTester implements AutoCloseable {
    private final PlatformStateModifier platformState;
    private final StateLifecycleManager stateLifecycleManager;
    private final DefaultTransactionHandler defaultTransactionHandler;
    private final List<PlatformStatusAction> submittedActions = new ArrayList<>();
    private final List<Round> handledRounds = new ArrayList<>();
    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private final MerkleNodeState consensusState;
    private final MockedStatic<PlatformStateFacade> platformStateFacadeMock;
    private final List<org.hiero.base.crypto.Hash> legacyRunningHashes = new ArrayList<>();
    private int updateLastFrozenInvocations = 0;

    /**
     * Constructs a new {@link TransactionHandlerTester} with the given {@link Roster}.
     *
     */
    public TransactionHandlerTester() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        platformState = new PlatformStateValueAccumulator();

        consensusState = mock(MerkleNodeState.class);
        when(consensusState.getRoot()).thenReturn(mock(MerkleNode.class));
        // Set up static mocks for PlatformStateFacade
        platformStateFacadeMock = org.mockito.Mockito.mockStatic(PlatformStateFacade.class);

        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);
        when(consensusState.copy()).thenReturn(consensusState);

        // Stub bulk update to operate on our in-memory PlatformStateValueAccumulator
        platformStateFacadeMock
                .when(() -> PlatformStateFacade.bulkUpdateOf(same(consensusState), any()))
                .thenAnswer(invocation -> {
                    final java.util.function.Consumer<PlatformStateModifier> updater = invocation.getArgument(1);
                    updater.accept(platformState);
                    return null;
                });

        // By default, not in freeze period
        platformStateFacadeMock
                .when(() -> PlatformStateFacade.isInFreezePeriod(any(java.time.Instant.class), same(consensusState)))
                .thenReturn(false);

        // Capture the legacy running hash updates instead of touching a real state
        platformStateFacadeMock
                .when(() -> PlatformStateFacade.setLegacyRunningEventHashTo(same(consensusState), any()))
                .thenAnswer(invocation -> {
                    legacyRunningHashes.add(invocation.getArgument(1));
                    return null;
                });

        // Track updateLastFrozenTime invocations
        platformStateFacadeMock
                .when(() -> PlatformStateFacade.updateLastFrozenTime(same(consensusState)))
                .thenAnswer(invocation -> {
                    updateLastFrozenInvocations++;
                    return null;
                });

        when(consensusStateEventHandler.onSealConsensusRound(any(), any())).thenReturn(true);
        doAnswer(i -> {
                    handledRounds.add(i.getArgument(0));
                    return null;
                })
                .when(consensusStateEventHandler)
                .onHandleConsensusRound(any(), same(consensusState), any());
        final StatusActionSubmitter statusActionSubmitter = submittedActions::add;
        stateLifecycleManager = new StateLifecycleManagerImpl(
                platformContext.getMetrics(), platformContext.getTime(), vm -> consensusState);
        stateLifecycleManager.initState(consensusState, true);
        defaultTransactionHandler = new DefaultTransactionHandler(
                platformContext,
                stateLifecycleManager,
                statusActionSubmitter,
                mock(SemanticVersion.class),
                consensusStateEventHandler,
                NodeId.of(1));
    }

    /**
     * @return the {@link DefaultTransactionHandler} used by this tester
     */
    public DefaultTransactionHandler getTransactionHandler() {
        return defaultTransactionHandler;
    }

    /**
     * @return the {@link PlatformStateModifier} used by this tester
     */
    public PlatformStateModifier getPlatformState() {
        return platformState;
    }

    /**
     * @return a list of all {@link PlatformStatusAction}s that have been submitted by the transaction handler
     */
    public List<PlatformStatusAction> getSubmittedActions() {
        return submittedActions;
    }

    /**
     * @return a list of all {@link Round}s that have been provided to the {@link State} for handling
     */
    public List<Round> getHandledRounds() {
        return handledRounds;
    }

    /**
     * @return the {@link StateLifecycleManager} used by this tester
     */
    public StateLifecycleManager getStateLifecycleManager() {
        return stateLifecycleManager;
    }

    /**
     * @return the {@link ConsensusStateEventHandler} used by this tester
     */
    public ConsensusStateEventHandler<MerkleNodeState> getStateEventHandler() {
        return consensusStateEventHandler;
    }

    /**
     * @return the static mock for PlatformStateFacade used by this tester
     */
    public MockedStatic<PlatformStateFacade> getPlatformStateFacadeMock() {
        return platformStateFacadeMock;
    }

    public State getConsensusState() {
        return consensusState;
    }

    /**
     * @return the list of legacy running hashes that were set on the state
     */
    public List<org.hiero.base.crypto.Hash> getLegacyRunningHashes() {
        return legacyRunningHashes;
    }

    /**
     * @return the number of times updateLastFrozenTime was invoked for the state
     */
    public int getUpdateLastFrozenInvocations() {
        return updateLastFrozenInvocations;
    }

    @Override
    public void close() {
        if (platformStateFacadeMock != null) {
            platformStateFacadeMock.close();
        }
    }
}
