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

package com.hedera.node.app.hints.handlers;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.HintsController;
import com.hedera.node.app.hints.impl.HintsControllers;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.ReadableRosterStore;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterEntryBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
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
                .thenReturn(new NodeInfoImpl(0L, asAccount(0L, 0L, 3L), 10L, List.of(), Bytes.wrap("test")));
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
        when(controllers.getAnyInProgress()).thenReturn(Optional.of(controller));
        when(hintsStore.getCrsState())
                .thenReturn(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .crs(INITIAL_CRS)
                        .nextContributingNodeId(0L)
                        .build());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(hintsStore).addCrsPublication(0L, CrsPublicationTransactionBody.DEFAULT);
        verify(controller).addCrsPublication(any(), any(), any());
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

    private static Roster createRoster() {
        List<RosterEntry> rosterEntries = new ArrayList<>();
        rosterEntries.add(RandomRosterEntryBuilder.create(new Random())
                .withNodeId(0L)
                .withWeight(10L)
                .build());
        return new Roster(rosterEntries);
    }
}
