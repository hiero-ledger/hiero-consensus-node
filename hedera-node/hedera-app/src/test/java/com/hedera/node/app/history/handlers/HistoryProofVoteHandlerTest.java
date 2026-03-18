// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofVoteTransactionBody;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofController;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryProofVoteHandlerTest {
    private static final long NODE_ID = 123L;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L);

    @Mock
    private ProofControllers controllers;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext context;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private ProofController controller;

    @Mock
    private WritableHistoryStore store;

    @Mock
    private StoreFactory factory;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    private HistoryProofVoteHandler subject;

    @BeforeEach
    void setUp() {
        subject = new HistoryProofVoteHandler(controllers);
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void handleIsNoopWithoutActiveConstruction() {
        givenVoteWith(1L, HistoryProofVote.DEFAULT);
        given(context.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);

        subject.handle(context);

        verify(controllers).getInProgressById(1L, tssConfig);
    }

    @Test
    void handleForwardsVoteWithActiveConstruction() {
        givenVoteWith(1L, HistoryProofVote.DEFAULT);
        given(controllers.getInProgressById(1L, tssConfig)).willReturn(Optional.of(controller));
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);

        subject.handle(context);

        verify(controllers).getInProgressById(1L, tssConfig);
        verify(controller).addProofVote(NODE_ID, HistoryProofVote.DEFAULT, CONSENSUS_NOW, store);
    }

    private void givenVoteWith(final long constructionId, @NonNull final HistoryProofVote vote) {
        final var op = new HistoryProofVoteTransactionBody(constructionId, vote);
        final var body = TransactionBody.newBuilder().historyProofVote(op).build();
        given(context.body()).willReturn(body);
    }
}
