// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static org.hiero.consensus.platformstate.PlatformStateUtils.bulkUpdateOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.platformstate.PlatformStateModifier;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.hiero.consensus.platformstate.PlatformStateValueAccumulator;
import org.hiero.consensus.state.signed.SignedState;

/**
 * A helper class for testing the {@link DefaultTransactionHandler}.
 */
public class TransactionHandlerTester implements AutoCloseable {
    private final PlatformStateModifier platformState;
    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private final DefaultTransactionHandler defaultTransactionHandler;
    private final List<PlatformStatusAction> submittedActions = new ArrayList<>();
    private final List<Round> handledRounds = new ArrayList<>();
    private final ConsensusStateEventHandler consensusStateEventHandler;
    private final Instant freezeTime;
    private final Instant consensusTimestamp;

    /**
     * Constructs a new {@link TransactionHandlerTester} with the given {@link Roster}.
     *
     */
    public TransactionHandlerTester() {

        freezeTime = Instant.now();
        consensusTimestamp = freezeTime.minusMillis(1);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        platformState = new PlatformStateValueAccumulator();
        final RandomSignedStateGenerator randomSignedStateGenerator = new RandomSignedStateGenerator();
        final SignedState state = randomSignedStateGenerator.build();

        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);

        when(consensusStateEventHandler.onSealConsensusRound(any(), any())).thenReturn(true);
        stateLifecycleManager = new StateLifecycleManagerImpl(
                platformContext.getMetrics(),
                platformContext.getTime(),
                vm -> state.getState(),
                platformContext.getConfiguration());
        stateLifecycleManager.initState(state.getState());
        doAnswer(i -> {
                    handledRounds.add(i.getArgument(0));
                    return null;
                })
                .when(consensusStateEventHandler)
                .onHandleConsensusRound(any(), same(stateLifecycleManager.getMutableState()), any());
        final StatusActionSubmitter statusActionSubmitter = submittedActions::add;
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
    public StateLifecycleManager<VirtualMapState, VirtualMap> getStateLifecycleManager() {
        return stateLifecycleManager;
    }

    /**
     * @return the {@link ConsensusStateEventHandler} used by this tester
     */
    public ConsensusStateEventHandler getStateEventHandler() {
        return consensusStateEventHandler;
    }

    /**
     * @return the list of legacy running hashes that were set on the state
     */
    public Hash getLegacyRunningHash() {
        return PlatformStateUtils.legacyRunningEventHashOf(stateLifecycleManager.getMutableState());
    }

    public void enableFreezePeriod() {
        bulkUpdateOf(stateLifecycleManager.getMutableState(), platformStateModifier -> {
            platformStateModifier.setConsensusTimestamp(consensusTimestamp);
            platformStateModifier.setFreezeTime(freezeTime);
        });
    }

    @Override
    public void close() {
        while (stateLifecycleManager.getLatestImmutableState().getRoot().getReservationCount() != -1) {
            stateLifecycleManager.getLatestImmutableState().release();
        }
        while (stateLifecycleManager.getMutableState().getRoot().getReservationCount() != -1) {
            stateLifecycleManager.getMutableState().release();
        }
    }
}
