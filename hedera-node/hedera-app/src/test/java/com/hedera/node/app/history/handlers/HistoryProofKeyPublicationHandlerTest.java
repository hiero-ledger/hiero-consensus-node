// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.hapi.services.auxiliary.history.HistoryProofKeyPublicationTransactionBody;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.HintsKeyWitnessVerifier;
import com.hedera.node.app.history.impl.ProofController;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
    private static final long CONSTRUCTION_ID = 42L;
    private static final Bytes PROOF_KEY = Bytes.wrap("PK");
    private static final Bytes WRAPS_MESSAGE = Bytes.wrap("MSG");
    private static final HintsPartialSignatureTransactionBody COMPANION =
            HintsPartialSignatureTransactionBody.newBuilder()
                    .constructionId(7)
                    .message(Bytes.wrap("successor verification key"))
                    .partialSignature(Bytes.wrap("source epoch share"))
                    .build();
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private ProofControllers controllers;

    @Mock
    private HintsKeyWitnessVerifier witnessVerifier;

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
        subject = new HistoryProofKeyPublicationHandler(controllers, witnessVerifier);
    }

    @Test
    void publishesWitnessForAdoptedGenerationIndependentlyOfProofController() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(CONSENSUS_NOW.minusSeconds(2));
        givenWitnessPublication(witness, record);
        given(witnessVerifier.verify(witness, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(true);
        given(store.setKeyWitness(witness)).willReturn(true);

        subject.handle(context);

        verify(store).setKeyWitness(witness);
        verifyNoInteractions(controllers);
    }

    @Test
    void allowsHistoricalBackfillWithoutInventingAnActivationBoundary() {
        // Use positive timestamps while making the witness a year older than publication.
        final var publicationTime = CONSENSUS_NOW.plusSeconds(31_536_000);
        final var historical = witnessAt(CONSENSUS_NOW);
        final var record = witnessRecord(null);
        givenWitnessPublication(historical, record);
        given(context.consensusNow()).willReturn(publicationTime);
        given(witnessVerifier.verify(historical, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(true);

        subject.handle(context);

        verify(store).setKeyWitness(historical);
        verifyNoInteractions(controllers);
    }

    @Test
    void rejectsCertificateForUnadoptedCandidate() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        givenWitnessPublication(witness, null);

        subject.handle(context);

        verify(store, never()).setKeyWitness(any());
        verifyNoInteractions(witnessVerifier, controllers);
    }

    @Test
    void duplicateWitnessCannotReplaceFirstConsensusAcceptedProof() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null)
                .copyBuilder()
                .witness(witnessAt(CONSENSUS_NOW.minusSeconds(2)))
                .build();
        givenWitnessPublication(witness, record);

        subject.handle(context);

        verify(store, never()).setKeyWitness(any());
        verifyNoInteractions(witnessVerifier, controllers);
    }

    @Test
    void rejectsBlockAtOrBeforeActivationBoundary() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        givenWitnessPublication(witness, witnessRecord(CONSENSUS_NOW.minusSeconds(1)));

        subject.handle(context);

        verify(store, never()).setKeyWitness(any());
        verifyNoInteractions(witnessVerifier);
    }

    @Test
    void rejectsFutureTimestamp() {
        final var witness = witnessAt(CONSENSUS_NOW.plusSeconds(1));
        givenWitnessPublication(witness, witnessRecord(null));

        subject.handle(context);

        verify(store, never()).setKeyWitness(any());
        verifyNoInteractions(witnessVerifier);
    }

    @Test
    void rejectsSignatureThatDoesNotVerifyUnderAdoptedKey() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null);
        givenWitnessPublication(witness, record);
        given(witnessVerifier.verify(witness, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(false);

        subject.handle(context);

        verify(store, never()).setKeyWitness(any());
    }

    @Test
    void malformedWitnessNeverReachesNativeVerification() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1))
                .copyBuilder()
                .siblingHash(Bytes.wrap("short"))
                .build();
        givenWitnessPublication(witness, witnessRecord(null));

        subject.handle(context);

        verify(store, never()).setKeyWitness(any());
        verifyNoInteractions(witnessVerifier);
    }

    private static HintsKeyWitness witnessAt(final Instant time) {
        return HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(CONSTRUCTION_ID)
                .timestamp(new Timestamp(time.getEpochSecond(), time.getNano()))
                .siblingHash(Bytes.wrap(new byte[48]))
                .signature(Bytes.wrap(new byte[HintsLibraryImpl.SIGNATURE_LENGTH]))
                .hashAlgorithm(BlockHashAlgorithm.SHA2_384)
                .build();
    }

    private static HintsKeyWitnessRecord witnessRecord(@Nullable final Instant activationTime) {
        return HintsKeyWitnessRecord.newBuilder()
                .provenance(HintsKeyProvenance.newBuilder()
                        .sourceHintsConstructionId(CONSTRUCTION_ID - 1)
                        .targetHintsConstructionId(CONSTRUCTION_ID)
                        .targetVerificationKey(Bytes.wrap(new byte[HintsLibraryImpl.VK_LENGTH]))
                        .build())
                .activationTime(
                        activationTime == null
                                ? null
                                : new Timestamp(activationTime.getEpochSecond(), activationTime.getNano()))
                .build();
    }

    private void givenWitnessPublication(final HintsKeyWitness witness, @Nullable final HintsKeyWitnessRecord record) {
        given(context.body())
                .willReturn(TransactionBody.newBuilder()
                        .historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                                .keyWitness(witness)
                                .build())
                        .build());
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(store.getKeyWitnessRecord(witness.targetHintsConstructionId())).willReturn(record);
        lenient().when(context.consensusNow()).thenReturn(CONSENSUS_NOW);
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void ifProofKeyIsImmediatelyActiveTriesToAddToRelevantController() {
        givenProofKeyPublicationWith(PROOF_KEY);
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
        givenProofKeyPublicationWith(PROOF_KEY);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(store.setProofKey(NODE_ID, PROOF_KEY, CONSENSUS_NOW)).willReturn(false);

        subject.handle(context);

        verifyNoInteractions(controllers);
    }

    @Test
    void wrapsMessageGivenToInProgressControllerAndPersistedWhenAccepted() {
        givenWrapsMessagePublicationWith(WRAPS_MESSAGE, WrapsPhase.R1);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(controllers.getInProgressById(CONSTRUCTION_ID)).willReturn(Optional.of(controller));
        given(controller.addWrapsMessagePublication(any(ReadableHistoryStore.WrapsMessagePublication.class), eq(store)))
                .willReturn(true);
        given(controller.constructionId()).willReturn(CONSTRUCTION_ID);

        subject.handle(context);

        final var captor = ArgumentCaptor.forClass(ReadableHistoryStore.WrapsMessagePublication.class);
        verify(controller).addWrapsMessagePublication(captor.capture(), eq(store));
        final var publication = captor.getValue();
        assertEquals(NODE_ID, publication.nodeId());
        assertEquals(WRAPS_MESSAGE, publication.message());
        assertEquals(WrapsPhase.R1, publication.phase());
        assertEquals(CONSENSUS_NOW, publication.receiptTime());
        assertNull(publication.hintsPartialSignature());
        verify(store).addWrapsMessage(CONSTRUCTION_ID, publication);
        verify(controllers, never()).getAnyInProgress();
    }

    @Test
    void companionStaysPairedWithItsCreatorAndWrapsMessageWhenPersisted() {
        givenWrapsMessagePublicationWith(WRAPS_MESSAGE, WrapsPhase.R1, CONSTRUCTION_ID, COMPANION);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(controllers.getInProgressById(CONSTRUCTION_ID)).willReturn(Optional.of(controller));
        given(controller.addWrapsMessagePublication(any(ReadableHistoryStore.WrapsMessagePublication.class), eq(store)))
                .willReturn(true);
        given(controller.constructionId()).willReturn(CONSTRUCTION_ID);

        subject.handle(context);

        final var captor = ArgumentCaptor.forClass(ReadableHistoryStore.WrapsMessagePublication.class);
        verify(controller).addWrapsMessagePublication(captor.capture(), eq(store));
        final var publication = captor.getValue();
        assertEquals(NODE_ID, publication.nodeId());
        assertEquals(WRAPS_MESSAGE, publication.message());
        assertEquals(WrapsPhase.R1, publication.phase());
        assertEquals(CONSENSUS_NOW, publication.receiptTime());
        assertEquals(COMPANION, publication.hintsPartialSignature());
        assertEquals(7, publication.hintsPartialSignature().constructionId());
        verify(store).addWrapsMessage(CONSTRUCTION_ID, publication);
    }

    @Test
    void doesNotPersistWrapsMessageIfControllerRejects() {
        givenWrapsMessagePublicationWith(WRAPS_MESSAGE, WrapsPhase.R1, CONSTRUCTION_ID, COMPANION);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(context.consensusNow()).willReturn(CONSENSUS_NOW);
        given(controllers.getInProgressById(CONSTRUCTION_ID)).willReturn(Optional.of(controller));
        given(controller.addWrapsMessagePublication(any(ReadableHistoryStore.WrapsMessagePublication.class), eq(store)))
                .willReturn(false);

        subject.handle(context);

        verify(controller)
                .addWrapsMessagePublication(any(ReadableHistoryStore.WrapsMessagePublication.class), eq(store));
        verify(store, never()).addWrapsMessage(anyLong(), any());
    }

    @Test
    void ignoresWrapsMessageFromReplacedConstruction() {
        final long staleConstructionId = CONSTRUCTION_ID - 1;
        givenWrapsMessagePublicationWith(WRAPS_MESSAGE, WrapsPhase.R1, staleConstructionId);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(controllers.getInProgressById(staleConstructionId)).willReturn(Optional.empty());

        subject.handle(context);

        verify(controllers).getInProgressById(staleConstructionId);
        verify(controllers, never()).getAnyInProgress();
        verifyNoInteractions(controller, store);
    }

    private void givenProofKeyPublicationWith(@NonNull final Bytes key) {
        final var op = HistoryProofKeyPublicationTransactionBody.newBuilder()
                .proofKey(key)
                .phase(WrapsPhase.R1)
                .build();
        final var body =
                TransactionBody.newBuilder().historyProofKeyPublication(op).build();
        given(context.body()).willReturn(body);
    }

    private void givenWrapsMessagePublicationWith(@NonNull final Bytes message, @NonNull final WrapsPhase phase) {
        givenWrapsMessagePublicationWith(message, phase, CONSTRUCTION_ID);
    }

    private void givenWrapsMessagePublicationWith(
            @NonNull final Bytes message, @NonNull final WrapsPhase phase, final long constructionId) {
        givenWrapsMessagePublicationWith(message, phase, constructionId, null);
    }

    private void givenWrapsMessagePublicationWith(
            @NonNull final Bytes message,
            @NonNull final WrapsPhase phase,
            final long constructionId,
            @Nullable final HintsPartialSignatureTransactionBody companion) {
        final var op = HistoryProofKeyPublicationTransactionBody.newBuilder()
                .wrapsMessage(message)
                .phase(phase)
                .constructionId(constructionId)
                .hintsPartialSignature(companion)
                .build();
        final var body =
                TransactionBody.newBuilder().historyProofKeyPublication(op).build();
        given(context.body()).willReturn(body);
    }
}
