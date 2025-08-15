// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofKeyPublicationTransactionBody;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofController;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryProofKeyPublicationHandlerTest {
    private static final long NODE_ID = 123L;
    private static final Bytes PROOF_KEY = Bytes.wrap("PK");
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

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

    private HistoryProofKeyPublicationHandler subject;

    @BeforeEach
    void setUp() {
        subject = new HistoryProofKeyPublicationHandler(controllers);
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void ifProofKeyIsImmediatelyActiveTriesToAddToRelevantController() {
        givenPublicationWith(PROOF_KEY);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(store.setProofKey(NODE_ID, PROOF_KEY, CONSENSUS_NOW)).willReturn(true);
        given(controllers.getAnyInProgress()).willReturn(Optional.of(controller));

        subject.handle(context);

        final var captor = ArgumentCaptor.forClass(ReadableHistoryStore.ProofKeyPublication.class);
        verify(controller).addProofKeyPublication(captor.capture());
        final var publication = captor.getValue();
        assertEquals(NODE_ID, publication.nodeId());
        assertEquals(PROOF_KEY, publication.proofKey());
    }

    @Test
    void doesNothingMoreIfProofKeyIsNotImmediately() {
        givenPublicationWith(PROOF_KEY);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(store.setProofKey(NODE_ID, PROOF_KEY, CONSENSUS_NOW)).willReturn(false);

        subject.handle(context);

        verifyNoInteractions(controllers);
    }

    private void givenPublicationWith(@NonNull final Bytes key) {
        final var op = new HistoryProofKeyPublicationTransactionBody(key);
        final var body =
                TransactionBody.newBuilder().historyProofKeyPublication(op).build();
        given(context.body()).willReturn(body);
    }
}
