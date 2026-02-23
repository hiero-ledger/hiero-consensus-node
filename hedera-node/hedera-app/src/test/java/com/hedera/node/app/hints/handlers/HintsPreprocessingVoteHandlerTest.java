// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPreprocessingVoteTransactionBody;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.HintsController;
import com.hedera.node.app.hints.impl.HintsControllers;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsPreprocessingVoteHandlerTest {
    private static final long NODE_ID = 123L;

    @Mock
    private HintsControllers controllers;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext context;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private HintsController controller;

    @Mock
    private WritableHintsStore store;

    @Mock
    private StoreFactory factory;

    private HintsPreprocessingVoteHandler subject;

    @BeforeEach
    void setUp() {
        subject = new HintsPreprocessingVoteHandler(controllers);
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void handleIsNoopWithoutActiveConstruction() {
        givenVoteWith(1L, PreprocessingVote.DEFAULT);

        subject.handle(context);

        verify(controllers).getInProgressById(1L);
        verifyNoMoreInteractions(context);
    }

    @Test
    void handleForwardsVoteWithActiveConstruction() {
        givenVoteWith(1L, PreprocessingVote.DEFAULT);
        given(controllers.getInProgressById(1L)).willReturn(Optional.of(controller));
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHintsStore.class)).willReturn(store);

        subject.handle(context);

        verify(controllers).getInProgressById(1L);
        verify(controller).addPreprocessingVote(NODE_ID, PreprocessingVote.DEFAULT, store);
        verifyNoMoreInteractions(context);
    }

    private void givenVoteWith(final long constructionId, @NonNull final PreprocessingVote vote) {
        final var op = new HintsPreprocessingVoteTransactionBody(constructionId, vote);
        final var body = TransactionBody.newBuilder().hintsPreprocessingVote(op).build();
        given(context.body()).willReturn(body);
    }
}
