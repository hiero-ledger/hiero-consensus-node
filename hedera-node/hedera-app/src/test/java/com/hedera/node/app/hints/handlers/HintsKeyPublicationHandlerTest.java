// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsKeyPublicationTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.HintsController;
import com.hedera.node.app.hints.impl.HintsControllers;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsKeyPublicationHandlerTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final long NODE_ID = 123L;
    private static final Bytes HINTS_KEY = Bytes.wrap("HK");

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

    private HintsKeyPublicationHandler subject;

    @BeforeEach
    void setUp() {
        subject = new HintsKeyPublicationHandler(controllers);
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void handleIsNoopWithoutMatchingConstruction() {
        givenPublicationWith(1, 2, HINTS_KEY);

        subject.handle(context);

        verify(controllers).getInProgressForNumParties(2);
        verifyNoMoreInteractions(context);
    }

    @Test
    void ignoresKeysWithIncorrectPartyId() {
        givenPublicationWith(3, 2, HINTS_KEY);
        given(controllers.getInProgressForNumParties(2)).willReturn(Optional.of(controller));
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(controller.partyIdOf(NODE_ID)).willReturn(OptionalInt.of(1));
        given(nodeInfo.nodeId()).willReturn(NODE_ID);

        subject.handle(context);

        verify(context, never()).storeFactory();
    }

    @Test
    void handleForwardsPublicationGivenCorrectPartyIdAndImmediatelyAdoptedKey() {
        givenPublicationWith(1, 2, HINTS_KEY);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(controllers.getInProgressForNumParties(anyInt())).willReturn(Optional.of(controller));
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(controller.partyIdOf(NODE_ID)).willReturn(OptionalInt.of(1));
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHintsStore.class)).willReturn(store);
        given(store.setHintsKey(NODE_ID, 1, 2, HINTS_KEY, CONSENSUS_NOW)).willReturn(true);
        given(store.getCrsState()).willReturn(CRSState.newBuilder().build());

        subject.handle(context);

        verify(controller)
                .addHintsKeyPublication(
                        new ReadableHintsStore.HintsKeyPublication(NODE_ID, HINTS_KEY, 1, CONSENSUS_NOW), Bytes.EMPTY);
    }

    @Test
    void doesNotForwardPublicationIfNotImmediatelyAdoptedKey() {
        givenPublicationWith(1, 2, HINTS_KEY);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(controllers.getInProgressForNumParties(2)).willReturn(Optional.of(controller));
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(controller.partyIdOf(NODE_ID)).willReturn(OptionalInt.of(1));
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHintsStore.class)).willReturn(store);

        subject.handle(context);

        verify(controller, never())
                .addHintsKeyPublication(
                        new ReadableHintsStore.HintsKeyPublication(NODE_ID, HINTS_KEY, 1, CONSENSUS_NOW), Bytes.EMPTY);
    }

    private void givenPublicationWith(final int partyId, final int numParties, @NonNull final Bytes hintsKey) {
        final var op = new HintsKeyPublicationTransactionBody(partyId, numParties, hintsKey);
        final var body = TransactionBody.newBuilder().hintsKeyPublication(op).build();
        given(context.body()).willReturn(body);
    }
}
