// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateValueAccumulator;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.State;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterRetriever;

/**
 * A helper class for testing the {@link DefaultTransactionHandler}.
 */
public class TransactionHandlerTester {
    private final PlatformStateModifier platformState;
    private final SwirldStateManager swirldStateManager;
    private final DefaultTransactionHandler defaultTransactionHandler;
    private final List<PlatformStatusAction> submittedActions = new ArrayList<>();
    private final List<Round> handledRounds = new ArrayList<>();
    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private final TestPlatformStateFacade platformStateFacade;
    private final MerkleNodeState consensusState;

    /**
     * Constructs a new {@link TransactionHandlerTester} with the given {@link AddressBook}.
     *
     * @param addressBook the {@link AddressBook} to use
     */
    public TransactionHandlerTester(final AddressBook addressBook) {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        platformState = new PlatformStateValueAccumulator();

        consensusState = mock(MerkleNodeState.class);
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
        swirldStateManager = new SwirldStateManager(
                platformContext,
                RosterRetriever.buildRoster(addressBook),
                NodeId.FIRST_NODE_ID,
                statusActionSubmitter,
                SemanticVersion.newBuilder().major(1).build(),
                consensusStateEventHandler,
                platformStateFacade);
        swirldStateManager.setInitialState(consensusState);
        defaultTransactionHandler = new DefaultTransactionHandler(
                platformContext,
                swirldStateManager,
                statusActionSubmitter,
                mock(SemanticVersion.class),
                platformStateFacade);
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
     * @return the {@link SwirldStateManager} used by this tester
     */
    public SwirldStateManager getSwirldStateManager() {
        return swirldStateManager;
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

    public State getConsensusState() {
        return consensusState;
    }
}
