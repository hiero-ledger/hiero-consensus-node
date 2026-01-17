// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private SchemaRegistry schemaRegistry;

    @Mock
    private WritableHintsStore hintsStore;

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

    @Mock
    private HintsHandlers handlers;

    private HintsServiceImpl subject;

    @BeforeEach
    void setUp() {
        subject = new HintsServiceImpl(component, library);
    }

    @Test
    void metadataAsExpected() {
        assertEquals(HintsService.NAME, subject.getServiceName());
        assertEquals(HintsService.MIGRATION_ORDER, subject.migrationOrder());
    }

    @Test
    void stopsControllersWorkWhenAsked() {
        given(component.controllers()).willReturn(controllers);

        subject.stop();

        verify(controllers).stop();
    }

    @Test
    void handoffIsNoop() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.HANDOFF);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verifyNoInteractions(hintsStore);
    }

    @Test
    void delegatesActiveConstruction() {
        given(context.activeConstruction()).willReturn(HintsConstruction.DEFAULT);
        given(component.signingContext()).willReturn(context);

        assertSame(HintsConstruction.DEFAULT, subject.activeConstruction());
    }

    @Test
    void doesNothingAtBootstrapIfTheConstructionIsComplete() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.BOOTSTRAP);
        final var construction =
                HintsConstruction.newBuilder().hintsScheme(HintsScheme.DEFAULT).build();
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verifyNoInteractions(component);
    }

    @Test
    void usesControllerIfTheConstructionIsIncompleteDuringTransition() {
        given(activeRosters.phase()).willReturn(ActiveRosters.Phase.TRANSITION);
        final var construction = HintsConstruction.DEFAULT;
        given(hintsStore.getOrCreateConstruction(activeRosters, CONSENSUS_NOW, tssConfig))
                .willReturn(construction);
        given(component.controllers()).willReturn(controllers);
        given(component.signingContext()).willReturn(context);
        given(context.activeConstruction()).willReturn(HintsConstruction.DEFAULT);
        given(controllers.getOrCreateFor(activeRosters, construction, hintsStore, HintsConstruction.DEFAULT))
                .willReturn(controller);

        subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW, tssConfig, true);

        verify(controller).advanceConstruction(CONSENSUS_NOW, hintsStore, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void registersTwoSchemasWhenHintsEnabled() {
        given(component.signingContext()).willReturn(context);

        subject.registerSchemas(schemaRegistry);

        final var captor = ArgumentCaptor.forClass(Schema.class);
        verify(schemaRegistry, times(2)).register(captor.capture());
        final var schemas = captor.getAllValues();
        assertThat(schemas.getFirst()).isInstanceOf(V059HintsSchema.class);
        assertThat(schemas.getLast()).isInstanceOf(V060HintsSchema.class);
    }

    @Test
    void delegatesHandlersAndKeyToComponent() {
        given(component.handlers()).willReturn(handlers);
        given(component.signingContext()).willReturn(context);
        given(context.verificationKeyOrThrow()).willReturn(Bytes.EMPTY);

        assertThat(subject.handlers()).isSameAs(handlers);
        assertThat(subject.activeVerificationKeyOrThrow()).isSameAs(Bytes.EMPTY);
    }
}
