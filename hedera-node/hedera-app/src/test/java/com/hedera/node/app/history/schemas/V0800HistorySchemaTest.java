// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID;
import static com.hedera.node.app.history.schemas.V0800HistorySchema.HINTS_KEY_WITNESSES_KEY;
import static com.hedera.node.app.history.schemas.V0800HistorySchema.HINTS_KEY_WITNESSES_STATE_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V0800HistorySchemaTest {
    private final HistoryService historyService = mock(HistoryService.class);
    private final V0800HistorySchema subject = new V0800HistorySchema(historyService);
    private final MigrationContext ctx = mock(MigrationContext.class);
    private final MapWritableKVState<ProtoLong, HintsKeyWitnessRecord> witnesses =
            new MapWritableKVState<>(HINTS_KEY_WITNESSES_STATE_ID, HINTS_KEY_WITNESSES_KEY);
    private final HintsKeyProvenance first = link(1, 2);
    private final HintsKeyProvenance second = link(2, 3);

    @Test
    void registersTypedCompanionMap() {
        final var definitions = subject.statesToCreate();
        assertEquals(1, definitions.size());
        final var definition = definitions.iterator().next();
        assertEquals(HINTS_KEY_WITNESSES_STATE_ID, definition.stateId());
        assertEquals(HINTS_KEY_WITNESSES_KEY, definition.stateKey());
        assertEquals(ProtoLong.PROTOBUF, definition.keyCodec());
        assertEquals(HintsKeyWitnessRecord.PROTOBUF, definition.valueCodec());
        assertFalse(definition.queue());
        assertFalse(definition.singleton());
    }

    @Test
    void genesisDoesNotInventAdoptionsOrWitnesses() {
        when(ctx.isGenesis()).thenReturn(true);
        subject.restart(ctx);
        verify(ctx, never()).newStates();
        verifyNoInteractions(historyService);
    }

    @Test
    void restartBackfillsOldQueueWithoutInventingTimesOrOverwritingCompletedWitnesses() {
        final var timestamp = asTimestamp(Instant.ofEpochSecond(123));
        final var completed = HintsKeyWitnessRecord.newBuilder()
                .provenance(first)
                .activationTime(timestamp)
                .witness(HintsKeyWitness.newBuilder()
                        .targetHintsConstructionId(2)
                        .timestamp(timestamp)
                        .signature(Bytes.wrap("existing signature"))
                        .build())
                .build();
        witnesses.put(new ProtoLong(2), completed);
        final var activeProof = HistoryProof.newBuilder()
                .keyProvenanceSignature(Bytes.wrap("active certificate"))
                .build();
        configure(activeProof);

        subject.restart(ctx);
        subject.restart(ctx);

        assertEquals(completed, witnesses.get(new ProtoLong(2)));
        final var backfilled = witnesses.get(new ProtoLong(3));
        assertEquals(second, backfilled.provenanceOrThrow());
        assertFalse(backfilled.hasActivationTime());
        assertFalse(backfilled.hasWitness());
        assertNull(witnesses.get(new ProtoLong(4)));
        verify(historyService, times(2)).setLatestHistoryProof(activeProof);
    }

    @Test
    void conflictingAdoptedBindingsAreRejectedWithoutReplacingTheCompanion() {
        final var wrong = HintsKeyWitnessRecord.newBuilder()
                .provenance(first.copyBuilder()
                        .targetVerificationKey(Bytes.wrap("different"))
                        .build())
                .build();
        witnesses.put(new ProtoLong(2), wrong);
        configure(HistoryProof.DEFAULT);
        assertThrows(IllegalStateException.class, () -> subject.restart(ctx));
        assertEquals(wrong, witnesses.get(new ProtoLong(2)));
        assertNull(witnesses.get(new ProtoLong(3)));
    }

    private void configure(final HistoryProof activeProof) {
        final var queue = ListWritableQueueState.<HintsKeyProvenance>builder(
                        HINTS_KEY_PROVENANCE_STATE_ID, "HINTS_KEY_PROVENANCE")
                .value(first)
                .value(second)
                .build();
        when(ctx.newStates())
                .thenReturn(new MapWritableStates(Map.of(
                        LEDGER_ID_STATE_ID, singleton(LEDGER_ID_STATE_ID, new ProtoBytes(Bytes.wrap(new byte[64]))),
                        ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                                singleton(
                                        ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                                        HistoryProofConstruction.newBuilder()
                                                .constructionId(3)
                                                .targetProof(activeProof)
                                                .build()),
                        NEXT_PROOF_CONSTRUCTION_STATE_ID,
                                singleton(NEXT_PROOF_CONSTRUCTION_STATE_ID, HistoryProofConstruction.DEFAULT),
                        HINTS_KEY_PROVENANCE_STATE_ID, queue,
                        HINTS_KEY_WITNESSES_STATE_ID, witnesses)));
        when(ctx.appConfig())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.historyEnabled", true)
                        .getOrCreateConfig());
    }

    private static HintsKeyProvenance link(final long source, final long target) {
        return HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(source)
                .targetHintsConstructionId(target)
                .historyConstructionId(target)
                .sourceVerificationKey(Bytes.wrap("key-" + source))
                .targetVerificationKey(Bytes.wrap("key-" + target))
                .signature(Bytes.wrap("certificate-" + target))
                .build();
    }

    private static <T> WritableSingletonState<T> singleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "HistoryService." + id, value::get, value::set);
    }
}
