// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.HintsController;
import com.hedera.node.app.hints.impl.HintsControllers;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.test.fixtures.RandomRosterEntryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CrsPublicationHandlerTest {
    @Mock
    private HintsControllers controllers;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private ReadableRosterStore rosterStore;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private HintsController controller;

    @InjectMocks
    private CrsPublicationHandler subject;

    private static final Bytes INITIAL_CRS = Bytes.wrap("initial crs".getBytes());
    // INITIAL_CRS is 11 bytes; proof must be exactly PROOF_LENGTH bytes
    private static final Bytes CONFORMING_CRS = Bytes.wrap(new byte[(int) INITIAL_CRS.length()]);
    private static final Bytes SHORT_CRS = Bytes.wrap(new byte[(int) INITIAL_CRS.length() - 1]);
    private static final Bytes LONG_CRS = Bytes.wrap(new byte[(int) INITIAL_CRS.length() + 1]);
    private static final Bytes CONFORMING_PROOF = Bytes.wrap(new byte[HintsLibrary.PROOF_LENGTH]);
    private static final Bytes SHORT_PROOF = Bytes.wrap(new byte[HintsLibrary.PROOF_LENGTH - 1]);
    private static final Bytes LONG_PROOF = Bytes.wrap(new byte[HintsLibrary.PROOF_LENGTH + 1]);

    @BeforeEach
    void setUp() {
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableHintsStore.class)).thenReturn(hintsStore);
        lenient().when(storeFactory.readableStore(ReadableRosterStore.class)).thenReturn(rosterStore);
        lenient()
                .when(handleContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .crsPublication(CrsPublicationTransactionBody.DEFAULT)
                        .build());
        lenient()
                .when(handleContext.creatorInfo())
                .thenReturn(new NodeInfoImpl(
                        0L, asAccount(0L, 0L, 3L), 10L, List.of(), Bytes.wrap("test"), List.of(), false, null));
        lenient().when(rosterStore.getActiveRoster()).thenReturn(createRoster());
        subject = new CrsPublicationHandler(controllers);
    }

    @Test
    void testConstructor() {
        assertNotNull(new CrsPublicationHandler(controllers));
    }

    @Test
    void testPreHandle() {
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void testPureChecks() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void testHandle() {
        final var conformingPublication = CrsPublicationTransactionBody.newBuilder()
                .newCrs(CONFORMING_CRS)
                .proof(CONFORMING_PROOF)
                .build();
        lenient()
                .when(handleContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .crsPublication(conformingPublication)
                        .build());
        when(controllers.getAnyInProgress()).thenReturn(Optional.of(controller));
        when(hintsStore.getCrsState())
                .thenReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .nextContributingNodeId(0L)
                        .build());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(hintsStore).addCrsPublication(0L, conformingPublication);
        verify(controller).addCrsPublication(any(), any(), any(), anyLong());
    }

    @Test
    void testHandleNoInProgressController() {
        when(controllers.getAnyInProgress()).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(hintsStore, never()).addCrsPublication(anyInt(), any());
    }

    @Test
    void testHandleNullContext() {
        assertThrows(NullPointerException.class, () -> subject.handle(null));
    }

    @Test
    void handleDoesNotPersistButStillAdvancesForTruncatedNewCrs() {
        final var malformedPublication = CrsPublicationTransactionBody.newBuilder()
                .newCrs(SHORT_CRS)
                .proof(CONFORMING_PROOF)
                .build();
        lenient()
                .when(handleContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .crsPublication(malformedPublication)
                        .build());
        when(controllers.getAnyInProgress()).thenReturn(Optional.of(controller));
        when(hintsStore.getCrsState())
                .thenReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .nextContributingNodeId(0L)
                        .build());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        // Malformed: must not be persisted
        verify(hintsStore, never()).addCrsPublication(anyLong(), any());
        // But the controller is still called so it can advance the ceremony
        verify(controller).addCrsPublication(any(), any(), any(), anyLong());
    }

    @Test
    void handleDoesNotPersistButStillAdvancesForExtendedNewCrs() {
        final var malformedPublication = CrsPublicationTransactionBody.newBuilder()
                .newCrs(LONG_CRS)
                .proof(CONFORMING_PROOF)
                .build();
        lenient()
                .when(handleContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .crsPublication(malformedPublication)
                        .build());
        when(controllers.getAnyInProgress()).thenReturn(Optional.of(controller));
        when(hintsStore.getCrsState())
                .thenReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .nextContributingNodeId(0L)
                        .build());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(hintsStore, never()).addCrsPublication(anyLong(), any());
        verify(controller).addCrsPublication(any(), any(), any(), anyLong());
    }

    @Test
    void handleDoesNotPersistButStillAdvancesForShortProof() {
        final var malformedPublication = CrsPublicationTransactionBody.newBuilder()
                .newCrs(CONFORMING_CRS)
                .proof(SHORT_PROOF)
                .build();
        lenient()
                .when(handleContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .crsPublication(malformedPublication)
                        .build());
        when(controllers.getAnyInProgress()).thenReturn(Optional.of(controller));
        when(hintsStore.getCrsState())
                .thenReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .nextContributingNodeId(0L)
                        .build());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(hintsStore, never()).addCrsPublication(anyLong(), any());
        verify(controller).addCrsPublication(any(), any(), any(), anyLong());
    }

    @Test
    void handleDoesNotPersistButStillAdvancesForExtendedProof() {
        final var malformedPublication = CrsPublicationTransactionBody.newBuilder()
                .newCrs(CONFORMING_CRS)
                .proof(LONG_PROOF)
                .build();
        lenient()
                .when(handleContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .crsPublication(malformedPublication)
                        .build());
        when(controllers.getAnyInProgress()).thenReturn(Optional.of(controller));
        when(hintsStore.getCrsState())
                .thenReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .nextContributingNodeId(0L)
                        .build());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(hintsStore, never()).addCrsPublication(anyLong(), any());
        verify(controller).addCrsPublication(any(), any(), any(), anyLong());
    }

    private static Roster createRoster() {
        List<RosterEntry> rosterEntries = new ArrayList<>();
        rosterEntries.add(RandomRosterEntryBuilder.create(new Random())
                .withNodeId(0L)
                .withWeight(10L)
                .build());
        return new Roster(rosterEntries);
    }
}
