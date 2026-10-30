// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V071HistorySchema.*;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class V0790HistorySchemaTest {
    private final HistoryService historyService = mock(HistoryService.class);
    private final V0790HistorySchema subject = new V0790HistorySchema(historyService);
    private final MigrationContext ctx = mock(MigrationContext.class);
    private final WritableSingletonState<ProtoBytes> ledgerId = singleton(LEDGER_ID_STATE_ID, null);
    private final WritableSingletonState<HistoryProofConstruction> active =
            singleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID, null);
    private final WritableSingletonState<HistoryProofConstruction> next =
            singleton(NEXT_PROOF_CONSTRUCTION_STATE_ID, null);

    @Test
    void createsProvenanceQueueAndRemovesTheWrapsProvingKeyHash() {
        final var definitions = subject.statesToCreate();
        assertEquals(1, definitions.size());
        final var definition = definitions.iterator().next();
        assertEquals(V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID, definition.stateId());
        assertEquals(V0790HistorySchema.HINTS_KEY_PROVENANCE_KEY, definition.stateKey());
        assertTrue(definition.queue());
        assertEquals(HintsKeyProvenance.PROTOBUF, definition.valueCodec());
        assertEquals(Set.of(WRAPS_PROVING_KEY_HASH_STATE_ID), subject.statesToRemove());
    }

    @Test
    void genesisLeavesStateInitializationForConsensus() {
        when(ctx.isGenesis()).thenReturn(true);
        subject.restart(ctx);
        verify(ctx, never()).newStates();
        verifyNoInteractions(historyService);
    }

    @Test
    void latestHookInitializesEmptyStateAndRestoresCompatibleProofOnRestart() {
        configure(true);
        subject.restart(ctx);
        subject.restart(ctx);
        assertEquals(ProtoBytes.DEFAULT, ledgerId.get());
        assertEquals(HistoryProofConstruction.DEFAULT, active.get());
        assertEquals(HistoryProofConstruction.DEFAULT, next.get());
        verifyNoInteractions(historyService);
        final var proof = HistoryProof.DEFAULT;
        ledgerId.put(new ProtoBytes(Bytes.wrap(new byte[64])));
        active.put(HistoryProofConstruction.newBuilder()
                .constructionId(1)
                .targetProof(proof)
                .build());
        subject.restart(ctx);
        verify(historyService).setLatestHistoryProof(proof);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsRealLegacyLedgerAndProofWithoutChangingTrustRoot(final boolean enabled) throws Exception {
        configure(enabled);
        final Network fixture;
        try (var input = getClass().getResourceAsStream("/tss/pre-nova-public-state.json")) {
            fixture = Network.JSON.parse(BufferedData.wrap(input.readAllBytes()));
        }
        ledgerId.put(new ProtoBytes(fixture.ledgerId()));
        active.put(fixture.tssMetadataOrThrow().activeProofConstructionOrThrow());
        assertEquals(32, ledgerId.get().value().length());
        final var failure = assertThrows(IllegalStateException.class, () -> subject.restart(ctx));
        assertTrue(failure.getMessage().contains("coordinated hinTS/history reset"));
        assertEquals(fixture.ledgerId(), ledgerId.get().value());
        assertEquals(fixture.tssMetadataOrThrow().activeProofConstructionOrThrow(), active.get());
        verifyNoInteractions(historyService);
    }

    private void configure(final boolean enabled) {
        when(ctx.newStates())
                .thenReturn(new MapWritableStates(Map.of(
                        LEDGER_ID_STATE_ID,
                        ledgerId,
                        ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                        active,
                        NEXT_PROOF_CONSTRUCTION_STATE_ID,
                        next)));
        when(ctx.appConfig())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.historyEnabled", enabled)
                        .getOrCreateConfig());
    }

    private static <T> WritableSingletonState<T> singleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "HistoryService." + id, value::get, value::set);
    }
}
