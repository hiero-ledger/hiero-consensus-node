// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    private HistoryProofKeyPublicationHandler subject;

    @BeforeEach
    void setUp() {
        subject = new HistoryProofKeyPublicationHandler(controllers, witnessVerifier);
        lenient().when(context.configuration()).thenReturn(configuration);
        lenient().when(preHandleContext.configuration()).thenReturn(configuration);
        lenient().when(configuration.getConfigData(TssConfig.class)).thenReturn(tssConfig);
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
        // Pre-handle cannot know the consensus time, so it may already have paired this publication
        verify(store).markWitnessRejected(CONSTRUCTION_ID, NODE_ID);
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
        verify(store).markWitnessRejected(CONSTRUCTION_ID, NODE_ID);
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

    @Test
    void ignoresWitnessesInMockModeWithoutReadingRecordsOrVerifying() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var body = TransactionBody.newBuilder()
                .historyProofKeyPublication(
                        HistoryProofKeyPublicationTransactionBody.newBuilder().keyWitness(witness))
                .build();
        given(context.body()).willReturn(body);
        given(preHandleContext.body()).willReturn(body);
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(tssConfig.forceMockSignatures()).willReturn(true);

        subject.preHandle(preHandleContext);
        subject.handle(context);

        verify(preHandleContext, never()).createStore(any());
        verifyNoInteractions(store, witnessVerifier, controllers);
    }

    @Test
    void preHandleCachesSuccessfulCryptoButHandleStillChecksCurrentState() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null);
        givenWitnessPublication(witness, record);
        givenPreHandleWitnessPublication();
        given(witnessVerifier.verify(witness, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(true);

        subject.preHandle(preHandleContext);
        subject.handle(context);
        verify(witnessVerifier, times(1))
                .verify(witness, record.provenanceOrThrow().targetVerificationKey());
        verify(store).setKeyWitness(witness);

        given(store.getKeyWitnessRecord(CONSTRUCTION_ID))
                .willReturn(record.copyBuilder().witness(witness).build());
        subject.handle(context);
        verify(store, times(1)).setKeyWitness(witness);
        verify(witnessVerifier, times(1)).verify(any(), any());
    }

    @Test
    void negativeCryptoResultsAreCachedAcrossPreHandleAndHandle() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null);
        givenWitnessPublication(witness, record);
        givenPreHandleWitnessPublication();

        subject.preHandle(preHandleContext);
        subject.handle(context);
        subject.preHandle(preHandleContext);
        subject.handle(context);

        verify(witnessVerifier, times(1))
                .verify(witness, record.provenanceOrThrow().targetVerificationKey());
        verify(store, never()).setKeyWitness(any());
    }

    @Test
    void preHandleResultCannotAuthorizeADifferentVerificationKey() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null);
        givenWitnessPublication(witness, record);
        givenPreHandleWitnessPublication();
        given(witnessVerifier.verify(witness, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(true);
        subject.preHandle(preHandleContext);

        final var changedKey = Bytes.wrap("different adopted key");
        given(store.getKeyWitnessRecord(CONSTRUCTION_ID))
                .willReturn(record.copyBuilder()
                        .provenance(record.provenanceOrThrow().copyBuilder().targetVerificationKey(changedKey))
                        .build());
        subject.handle(context);

        verify(witnessVerifier).verify(witness, changedKey);
        verify(store, never()).setKeyWitness(any());
    }

    @Test
    void adoptionAfterPreHandleStillVerifiesDeterministicallyAtHandle() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null);
        givenWitnessPublication(witness, null);
        givenPreHandleWitnessPublication();
        subject.preHandle(preHandleContext);
        verifyNoInteractions(witnessVerifier);

        given(store.getKeyWitnessRecord(CONSTRUCTION_ID)).willReturn(record);
        given(witnessVerifier.verify(witness, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(true);
        subject.handle(context);

        verify(witnessVerifier).verify(witness, record.provenanceOrThrow().targetVerificationKey());
        verify(store).setKeyWitness(witness);
    }

    @Test
    void cryptographicProofWithZeroBookkeepingIdCannotCreateNativeRecord() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1))
                .copyBuilder()
                .targetHintsConstructionId(0)
                .build();
        final var record = witnessRecord(null);
        givenWitnessPublication(
                witness,
                record.copyBuilder()
                        .provenance(record.provenanceOrThrow().copyBuilder().targetHintsConstructionId(0))
                        .build());
        givenPreHandleWitnessPublication();

        subject.preHandle(preHandleContext);
        subject.handle(context);

        verifyNoInteractions(witnessVerifier);
        verify(store, never()).setKeyWitness(any());
    }

    private void givenPreHandleWitnessPublication() {
        final var body = context.body();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableHistoryStore.class)).willReturn(store);
        givenCreator();
    }

    @Test
    void recordedCreatorCostsNoNativeWorkInPreHandleOrHandle() throws Exception {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null)
                .copyBuilder()
                .rejectedNodeIds(List.of(NODE_ID))
                .build();
        givenWitnessPublication(witness, record);
        givenPreHandleWitnessPublication();

        subject.preHandle(preHandleContext);
        subject.handle(context);

        verifyNoInteractions(witnessVerifier);
        verify(store, never()).setKeyWitness(any());
        verify(store, never()).markWitnessRejected(anyLong(), anyLong());
    }

    @Test
    void anotherCreatorsRecordedRejectionDoesNotBlockThisCreator() {
        final var witness = witnessAt(CONSENSUS_NOW.minusSeconds(1));
        final var record = witnessRecord(null)
                .copyBuilder()
                .rejectedNodeIds(List.of(NODE_ID + 1))
                .build();
        givenWitnessPublication(witness, record);
        given(witnessVerifier.verify(witness, record.provenanceOrThrow().targetVerificationKey()))
                .willReturn(true);
        given(store.setKeyWitness(witness)).willReturn(true);

        subject.handle(context);

        verify(store).setKeyWitness(witness);
        verify(store, never()).markWitnessRejected(anyLong(), anyLong());
    }

    @Test
    void aFloodOfUniqueForgeriesFromOneCreatorCostsExactlyOneFailedVerification() throws Exception {
        final var state = givenConsensusStateOf(witnessRecord(null));
        final var current = new AtomicReference<TransactionBody>();
        givenPublicationsFrom(current);

        for (int i = 0; i < 3_000; i++) {
            current.set(keyWitnessBody(forgery(i)));
            // Pre-handle also reruns on the handle thread, against handle-time state, before handle
            subject.preHandle(preHandleContext);
            subject.handle(context);
        }

        verify(witnessVerifier, times(1)).verify(any(), any());
        verify(store, times(1)).markWitnessRejected(CONSTRUCTION_ID, NODE_ID);
        assertEquals(List.of(NODE_ID), state.get().rejectedNodeIds());
        verify(store, never()).setKeyWitness(any());
    }

    @Test
    void forgeriesPreHandledBeforeTheRecordExistedStillCostOneFailedVerificationAtHandle() throws Exception {
        final var pending = witnessRecord(null);
        final var state = givenConsensusStateOf(null);
        final var current = new AtomicReference<TransactionBody>();
        givenPublicationsFrom(current);
        final var forgeries = new ArrayList<TransactionBody>();
        for (int i = 0; i < 500; i++) {
            forgeries.add(keyWitnessBody(forgery(i)));
        }
        // The adoption has not happened yet, so no worker has a record to verify against
        for (final var body : forgeries) {
            current.set(body);
            subject.preHandle(preHandleContext);
        }
        verifyNoInteractions(witnessVerifier);

        state.set(pending);
        for (final var body : forgeries) {
            current.set(body);
            subject.preHandle(preHandleContext);
            subject.handle(context);
        }

        verify(witnessVerifier, times(1)).verify(any(), any());
        assertEquals(List.of(NODE_ID), state.get().rejectedNodeIds());
    }

    @Test
    void workersThatPreHandledAWholeFloodFirstLeaveAtMostOneVerificationToHandle() throws Exception {
        final var state = givenConsensusStateOf(witnessRecord(null));
        final var current = new AtomicReference<TransactionBody>();
        givenPublicationsFrom(current);
        final var forgeries = new ArrayList<TransactionBody>();
        for (int i = 0; i < 3_000; i++) {
            forgeries.add(keyWitnessBody(forgery(i)));
        }
        // Worker-side pre-handle runs ahead of consensus, so the record cannot know the creator yet
        for (final var body : forgeries) {
            current.set(body);
            subject.preHandle(preHandleContext);
        }
        clearInvocations(witnessVerifier);

        for (final var body : forgeries) {
            current.set(body);
            subject.preHandle(preHandleContext);
            subject.handle(context);
        }

        // However much of the flood the bounded cache kept, the handle thread pays for at most one failure
        verify(witnessVerifier, atMost(1)).verify(any(), any());
        assertEquals(List.of(NODE_ID), state.get().rejectedNodeIds());
    }

    @Test
    void preHandleContainsFailuresBecauseItAlsoRunsOnTheHandleThread() {
        given(preHandleContext.body()).willReturn(keyWitnessBody(witnessAt(CONSENSUS_NOW.minusSeconds(1))));
        given(preHandleContext.createStore(ReadableHistoryStore.class))
                .willThrow(new IllegalStateException("state unavailable"));
        givenCreator();

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    private void givenCreator() {
        lenient().when(context.creatorInfo()).thenReturn(nodeInfo);
        lenient().when(preHandleContext.creatorInfo()).thenReturn(nodeInfo);
        lenient().when(nodeInfo.nodeId()).thenReturn(NODE_ID);
    }

    /** What consensus keeps: the first rejected publication records its creator on the companion record. */
    private AtomicReference<HintsKeyWitnessRecord> givenConsensusStateOf(
            @Nullable final HintsKeyWitnessRecord initial) {
        final var state = new AtomicReference<>(initial);
        given(store.getKeyWitnessRecord(CONSTRUCTION_ID)).willAnswer(invocation -> state.get());
        given(store.markWitnessRejected(eq(CONSTRUCTION_ID), anyLong())).willAnswer(invocation -> {
            final long nodeId = invocation.getArgument(1);
            state.set(state.get().copyBuilder().rejectedNodeIds(List.of(nodeId)).build());
            return true;
        });
        return state;
    }

    private void givenPublicationsFrom(final AtomicReference<TransactionBody> current) {
        given(context.body()).willAnswer(invocation -> current.get());
        given(preHandleContext.body()).willAnswer(invocation -> current.get());
        given(context.storeFactory()).willReturn(factory);
        given(factory.writableStore(WritableHistoryStore.class)).willReturn(store);
        given(preHandleContext.createStore(ReadableHistoryStore.class)).willReturn(store);
        lenient().when(context.consensusNow()).thenReturn(CONSENSUS_NOW);
        givenCreator();
    }

    /** Every forgery passes the cheap admission checks; unique nanos make each a distinct cache key. */
    private static HintsKeyWitness forgery(final int i) {
        return witnessAt(CONSENSUS_NOW.minusSeconds(1).plusNanos(i));
    }

    private static TransactionBody keyWitnessBody(final HintsKeyWitness witness) {
        return TransactionBody.newBuilder()
                .historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                        .keyWitness(witness)
                        .build())
                .build();
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
        givenCreator();
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        given(preHandleContext.body())
                .willReturn(TransactionBody.newBuilder()
                        .historyProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                                .proofKey(PROOF_KEY))
                        .build());
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
