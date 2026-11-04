// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.history.schemas.V071HistorySchema.*;
import static com.hedera.node.app.history.schemas.V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID;
import static com.hedera.node.app.history.schemas.V0800HistorySchema.HINTS_KEY_WITNESSES_STATE_ID;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofKeyPublicationTransactionBody;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryProofKeyPublicationHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.CommittableWritableStates;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises native fixture bytes through consensus admission, persistent state, and consumer catch-up. */
class DurableProvenanceFlowTest {
    @Test
    void admitsNativeWitnessOnlyAfterAdoptionAndRestoresItForHistoricalCatchUp() throws Exception {
        final var vectors = new ObjectMapper()
                .readTree(requireNonNull(getClass().getResourceAsStream("/provenance-witnesses/native-v1.json")));
        final var certificate = HintsKeyProvenance.PROTOBUF.parse(Bytes.fromHex(
                vectors.get("certificates").get(0).get("protobufHex").asText()));
        final var witness = HintsKeyWitness.PROTOBUF.parse(
                Bytes.fromHex(vectors.get("witnesses").get(0).get("protobufHex").asText()));
        final var state = stateWithPreparedCandidate(certificate);
        final var store = new WritableHistoryStoreImpl(state.getWritableStates(HistoryService.NAME));
        final var context = mock(HandleContext.class);
        when(context.configuration())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.forceMockSignatures", false)
                        .getOrCreateConfig());
        final var storeFactory = mock(StoreFactory.class);
        when(context.storeFactory()).thenReturn(storeFactory);
        when(storeFactory.writableStore(WritableHistoryStore.class)).thenReturn(store);
        when(context.body())
                .thenReturn(TransactionBody.newBuilder()
                        .historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                                .keyWitness(witness))
                        .build());
        // The same historical witness remains eligible a year after it was produced.
        when(context.consensusNow())
                .thenReturn(asInstant(witness.timestampOrThrow()).plusSeconds(31_536_000));
        final var verifier = new HintsKeyWitnessVerifier(new HintsLibraryImpl());
        final var handler = new HistoryProofKeyPublicationHandler(mock(ProofControllers.class), verifier);

        assertTrue(verifier.verify(witness, certificate.targetVerificationKey()));
        handler.handle(context);
        assertNull(store.getKeyWitnessRecord(certificate.targetHintsConstructionId()));
        assertFalse(store.keyProvenance().hasNext());

        assertTrue(store.handoff(
                Roster.DEFAULT,
                null,
                null,
                asInstant(witness.timestampOrThrow()).minusSeconds(1)));
        assertFalse(store.getKeyWitnessRecord(certificate.targetHintsConstructionId())
                .hasWitness());
        handler.handle(context);
        ((CommittableWritableStates) state.getWritableStates(HistoryService.NAME)).commit();

        final var restored = new WritableHistoryStoreImpl(state.getWritableStates(HistoryService.NAME));
        final var record = restored.keyProvenanceWithWitnesses().next();
        assertEquals(certificate, record.provenance());
        assertEquals(witness, record.witness());
        assertEquals(record, HintsKeyWitnessRecord.PROTOBUF.parse(HintsKeyWitnessRecord.PROTOBUF.toBytes(record)));

        final var checkpoint = new HintsKeyProvenanceVerifier.Checkpoint(
                certificate.sourceVerificationKey(),
                new Timestamp(vectors.get("initialTimestampSeconds").asLong(), 0));
        final var advanced = new HintsKeyProvenanceVerifier(verifier)
                .verifyBatch(checkpoint, List.of(record.provenanceOrThrow()), record.witnessOrThrow(), 1)
                .orElseThrow();
        assertEquals(certificate.targetVerificationKey(), advanced.verificationKey());
        assertEquals(witness.timestamp(), advanced.witnessedAt());

        // Replaying a different publication for this generation cannot overwrite the accepted proof.
        when(storeFactory.writableStore(WritableHistoryStore.class)).thenReturn(restored);
        when(context.body())
                .thenReturn(TransactionBody.newBuilder()
                        .historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                                .keyWitness(witness.copyBuilder()
                                        .signature(Bytes.wrap("corrupt"))
                                        .build()))
                        .build());
        handler.handle(context);
        assertEquals(
                witness,
                restored.getKeyWitnessRecord(certificate.targetHintsConstructionId())
                        .witness());
    }

    private static FakeState stateWithPreparedCandidate(final HintsKeyProvenance certificate) {
        final var state = new FakeState();
        final var next = HistoryProofConstruction.newBuilder()
                .constructionId(certificate.historyConstructionId())
                .targetMetadata(certificate.targetVerificationKey())
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(History.newBuilder().metadata(certificate.targetVerificationKey()))
                        .keyProvenanceSignature(certificate.signature()))
                .keyProvenance(certificate)
                .build();
        state.addService(
                HistoryService.NAME,
                Map.of(
                        LEDGER_ID_STATE_ID, new AtomicReference<>(ProtoBytes.DEFAULT),
                        ACTIVE_PROOF_CONSTRUCTION_STATE_ID, new AtomicReference<>(HistoryProofConstruction.DEFAULT),
                        NEXT_PROOF_CONSTRUCTION_STATE_ID, new AtomicReference<>(next),
                        PROOF_KEY_SETS_STATE_ID, new ConcurrentHashMap<>(),
                        PROOF_VOTES_STATE_ID, new ConcurrentHashMap<>(),
                        WRAPS_MESSAGE_HISTORIES_STATE_ID, new ConcurrentHashMap<>(),
                        HINTS_KEY_PROVENANCE_STATE_ID, new ConcurrentLinkedDeque<>(),
                        HINTS_KEY_WITNESSES_STATE_ID, new ConcurrentHashMap<>()));
        return state;
    }
}
