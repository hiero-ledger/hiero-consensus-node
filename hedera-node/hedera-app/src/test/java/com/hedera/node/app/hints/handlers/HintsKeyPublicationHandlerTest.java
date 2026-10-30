// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
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
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HintsKeyPublicationHandlerTest {
    private static final Instant NOW = Instant.ofEpochSecond(12345);
    private static final Bytes KEY = Bytes.wrap("key");
    private static final Bytes CRS = Bytes.wrap("crs");
    private static final HintsConstruction CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(3)
            .crsId(4)
            .numParties(8)
            .gracePeriodEndTime(asTimestamp(NOW.plusSeconds(10)))
            .build();
    private final HintsControllers controllers = mock(HintsControllers.class);
    private final HintsController controller = mock(HintsController.class);
    private final WritableHintsStore store = mock(WritableHintsStore.class);
    private final HandleContext context = mock(HandleContext.class);
    private final HintsKeyPublicationHandler subject = new HintsKeyPublicationHandler(controllers);

    @BeforeEach
    void setup() {
        final var factory = mock(StoreFactory.class);
        final var node = mock(NodeInfo.class);
        when(context.storeFactory()).thenReturn(factory);
        when(factory.writableStore(WritableHintsStore.class)).thenReturn(store);
        when(context.creatorInfo()).thenReturn(node);
        when(node.nodeId()).thenReturn(123L);
        when(context.consensusNow()).thenReturn(NOW);
        when(controllers.getInProgressById(3)).thenReturn(Optional.of(controller));
        when(controller.partyIdOf(123)).thenReturn(OptionalInt.of(1));
        when(store.getActiveConstruction()).thenReturn(CONSTRUCTION);
        when(store.getCrsStateFor(CONSTRUCTION))
                .thenReturn(
                        CRSState.newBuilder().stage(CRSStage.COMPLETED).crs(CRS).build());
        publication(3, 4, 1);
    }

    private void publication(long constructionId, long crsId, int partyId) {
        when(context.body())
                .thenReturn(TransactionBody.newBuilder()
                        .hintsKeyPublication(HintsKeyPublicationTransactionBody.newBuilder()
                                .constructionId(constructionId)
                                .crsId(crsId)
                                .partyId(partyId)
                                .numParties(8)
                                .hintsKey(KEY))
                        .build());
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(mock(PureChecksContext.class)));
        assertDoesNotThrow(() -> subject.preHandle(mock(PreHandleContext.class)));
    }

    @Test
    void forwardsOnlyImmediatelyAdoptedKeyForMatchingConstructionAndCrs() {
        when(store.setHintsKey(123, 1, 8, 4, KEY, NOW)).thenReturn(true);
        subject.handle(context);
        verify(controller).addHintsKeyPublication(new ReadableHintsStore.HintsKeyPublication(123, KEY, 1, NOW), CRS);
    }

    @Test
    void ignoresWrongConstructionCrsAndParty() {
        publication(2, 4, 1);
        subject.handle(context);
        publication(3, 2, 1);
        subject.handle(context);
        publication(3, 4, 2);
        subject.handle(context);
        verify(store, never()).setHintsKey(anyLong(), anyInt(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void ignoresKeysWhileCeremonyIsInProgressOrPreprocessingHasStarted() {
        when(store.getCrsStateFor(CONSTRUCTION)).thenReturn(CRSState.DEFAULT);
        subject.handle(context);
        when(store.getActiveConstruction())
                .thenReturn(CONSTRUCTION
                        .copyBuilder()
                        .preprocessingStartTime(asTimestamp(NOW))
                        .build());
        subject.handle(context);
        verify(store, never()).setHintsKey(anyLong(), anyInt(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void queuedKeyDoesNotChangeControllerInputs() {
        subject.handle(context);
        verify(store).setHintsKey(123, 1, 8, 4, KEY, NOW);
        verify(controller, never()).addHintsKeyPublication(any(), any());
    }
}
