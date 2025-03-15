// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateValueAccumulator;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.platform.test.fixtures.state.TestStateLifecycleManager;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StateLifecycleManager;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class for testing the {@link DefaultTransactionHandler}.
 */
public class TransactionHandlerTester {
    private final PlatformStateModifier platformState;
    private final DefaultTransactionHandler defaultTransactionHandler;
    private final List<PlatformStatusAction> submittedActions = new ArrayList<>();
    private final List<Round> handledRounds = new ArrayList<>();
    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private final TestPlatformStateFacade platformStateFacade;
    private final TestMerkleStateRoot consensusState;
    private final StateLifecycleManager<TestMerkleStateRoot> stateLifecycleManager;

    /**
     * Constructs a new {@link TransactionHandlerTester} with the given {@link AddressBook}.
     *
     * @param addressBook the {@link AddressBook} to use
     */
    public TransactionHandlerTester(final AddressBook addressBook) {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        platformState = new PlatformStateValueAccumulator();

        consensusState = mock(TestMerkleStateRoot.class);
        when(consensusState.getRoot()).thenReturn(mock(MerkleNode.class));
        platformStateFacade = mock(TestPlatformStateFacade.class);

        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);
        when(consensusState.copy()).thenReturn(consensusState);
        when(platformStateFacade.getWritablePlatformStateOf(consensusState)).thenReturn(platformState);

        when(consensusStateEventHandler.onSealConsensusRound(any(), any())).thenReturn(true);
        doAnswer(i -> {
                    handledRounds.add(i.getArgument(0));
                    return null;
                })
                .when(consensusStateEventHandler)
                .onHandleConsensusRound(any(), same(consensusState), any());
        final StatusActionSubmitter statusActionSubmitter = submittedActions::add;
        stateLifecycleManager = new TestStateLifecycleManager();
        defaultTransactionHandler = new DefaultTransactionHandler(
                platformContext,
                statusActionSubmitter,
                mock(SoftwareVersion.class),
                platformStateFacade,
                stateLifecycleManager,
                consensusStateEventHandler,
                RosterRetriever.buildRoster(addressBook),
                NodeId.FIRST_NODE_ID);

        stateLifecycleManager.setInitialState(consensusState);
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
     * @return the {@link ConsensusStateEventHandler} used by this tester
     */
    public ConsensusStateEventHandler<MerkleNodeState> getStateEventHandler() {
        return consensusStateEventHandler;
    }

    public PlatformStateFacade getPlatformStateFacade() {
        return platformStateFacade;
    }

    public TestMerkleStateRoot getConsensusState() {
        return consensusState;
    }
}
