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

package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.impl.WritableHintsStoreImplTest.WITH_ENABLED_HINTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsServiceImplTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private TssConfig tssConfig;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private SchemaRegistry schemaRegistry;

    @Mock
    private HintsServiceComponent component;

    @Mock
    private HintsContext context;

    @Mock
    private HintsControllers controllers;

    @Mock
    private HintsController controller;

    @Mock
    private HintsLibrary library;

    private HintsServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new HintsServiceImpl(WITH_ENABLED_HINTS, component, library);
    }

    @Test
    void metadataAsExpected() {
        assertEquals(HintsService.NAME, subject.getServiceName());
        assertEquals(HintsService.MIGRATION_ORDER, subject.migrationOrder());
    }

    @Test
    void initsSigningBySettingNextConstruction() {
        given(hintsStore.getNextConstruction()).willReturn(HintsConstruction.DEFAULT);
        given(component.signingContext()).willReturn(context);

        subject.initSigningForNextScheme(hintsStore);

        verify(context).setConstruction(HintsConstruction.DEFAULT);
    }

    @Test
    void stopsControllersWorkWhenAsked() {
        given(component.controllers()).willReturn(controllers);

        subject.stop();

        verify(controllers).stop();
    }

    @Test
    void nothingSupportedExceptRegisteringSchemas() {
        assertThrows(UnsupportedOperationException.class, subject::isReady);
        assertThrows(UnsupportedOperationException.class, subject::activeVerificationKeyOrThrow);
        assertThrows(UnsupportedOperationException.class, () -> subject.signFuture(Bytes.EMPTY));
        given(component.signingContext()).willReturn(context);
        assertDoesNotThrow(() -> subject.registerSchemas(schemaRegistry));
    }

    @Test
    void callPurgeAfterHandoff() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.HANDOFF);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig);

        verify(hintsStore).updateForHandoff(activeRosters);
    }

    @Test
    void doesNothingAtBootstrapIfTheConstructionIsComplete() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.BOOTSTRAP);
        final var construction =
                HintsConstruction.newBuilder().hintsScheme(HintsScheme.DEFAULT).build();
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig);

        verifyNoInteractions(component);
    }

    @Test
    void usesControllerIfTheConstructionIsIncompleteDuringTransition() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.TRANSITION);
        final var construction = HintsConstruction.DEFAULT;
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);
        given(component.controllers()).willReturn(controllers);
        given(controllers.getOrCreateFor(activeRosters, construction, hintsStore))
                .willReturn(controller);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig);

        verify(controller).advanceConstruction(CONSENSUS_NOW, hintsStore);
    }
}
