/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.history.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofVoteTransactionBody;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofController;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryProofVoteHandlerTest {
    private static final long NODE_ID = 123L;

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

        subject.handle(context);

        verify(controllers).getInProgressById(1L);
        verifyNoMoreInteractions(context);
    }

    @Test
    void handleForwardsVoteWithActiveConstruction() {
        givenVoteWith(1L, HistoryProofVote.DEFAULT);
        given(controllers.getInProgressById(1L)).willReturn(Optional.of(controller));
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);

        subject.handle(context);

        verify(controllers).getInProgressById(1L);
        verifyNoMoreInteractions(context);
    }

    private void givenVoteWith(final long constructionId, @NonNull final HistoryProofVote vote) {
        final var op = new HistoryProofVoteTransactionBody(constructionId, vote);
        final var body = TransactionBody.newBuilder().historyProofVote(op).build();
        given(context.body()).willReturn(body);
    }
}
