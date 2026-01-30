// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Instant.EPOCH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.AggregatedNodeSignatures;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WrapsHistoryProverTest {
    private static final long SELF_ID = 1L;
    private static final long OTHER_NODE_ID = 2L;
    private static final long CONSTRUCTION_ID = 123L;
    private static final Bytes LEDGER_ID = Bytes.wrap("ledger");
    private static final Bytes TARGET_METADATA = Bytes.wrap("meta");
    private static final Bytes MESSAGE_BYTES = Bytes.wrap("msg");
    private static final Bytes R1_MESSAGE = Bytes.wrap("r1");
    private static final Bytes R2_MESSAGE = Bytes.wrap("r2");
    private static final Bytes R3_MESSAGE = Bytes.wrap("r3");
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(5);

    private static final ProofKeysAccessorImpl.SchnorrKeyPair KEY_PAIR =
            new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.wrap("priv"), Bytes.wrap("pub"));

    @Mock
    private Executor executor;

    private final WrapsHistoryProver.Delayer delayer = (delay, unit, executor) -> executor;

    @Mock
    private HistoryLibrary historyLibrary;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private WritableHistoryStore writableHistoryStore;

    @Mock
    private TssConfig tssConfig;

    private final SortedMap<Long, Long> sourceWeights = new TreeMap<>();
    private final SortedMap<Long, Long> targetWeights = new TreeMap<>();
    private final Map<Long, Bytes> proofKeys = new TreeMap<>();
    private final Map<Long, Bytes> targetProofKeys = new TreeMap<>();

    private RosterTransitionWeights weights;

    private WrapsHistoryProver subject;
    private static final Bytes AGG_SIG = Bytes.wrap("aggSig");
    private static final Bytes UNCOMPRESSED = Bytes.wrap("uncompressed");
    private static final Bytes COMPRESSED = Bytes.wrap("compressed");

    @BeforeEach
    void setUp() {
        sourceWeights.put(SELF_ID, 1L);
        sourceWeights.put(OTHER_NODE_ID, 1L);
        targetWeights.put(SELF_ID, 1L);
        targetWeights.put(OTHER_NODE_ID, 1L);

        proofKeys.put(SELF_ID, Bytes.wrap("pk1"));
        proofKeys.put(OTHER_NODE_ID, Bytes.wrap("pk2"));
        targetProofKeys.putAll(proofKeys);

        weights = new RosterTransitionWeights(sourceWeights, targetWeights);

        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                null,
                weights,
                proofKeys,
                delayer,
                executor,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
    }

    private static HistoryProofConstruction constructionWithPhase(WrapsPhase phase, Instant graceEnd) {
        final var stateBuilder = WrapsSigningState.newBuilder().phase(phase);
        if (graceEnd != null) {
            stateBuilder.gracePeriodEndTime(new Timestamp(graceEnd.getEpochSecond(), graceEnd.getNano()));
        }
        return HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(stateBuilder.build())
                .build();
    }

    @Test
    void advanceFailsWhenNonGenesisWithoutLedgerId() {
        final var nonGenesisSourceProof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build();
        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                nonGenesisSourceProof,
                weights,
                proofKeys,
                delayer,
                executor,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());

        final var outcome = subject.advance(
                EPOCH, constructionWithPhase(R1, null), TARGET_METADATA, targetProofKeys, tssConfig, null);

        assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        final var failed = (HistoryProver.Outcome.Failed) outcome;
        assertTrue(failed.reason().contains("genesis WRAPS proofs"));
        verifyNoInteractions(submissions);
    }

    @Test
    void advanceFailsWhenGracePeriodExpired() {
        final var now = Instant.ofEpochSecond(10);
        final var graceEnd = Instant.ofEpochSecond(5);
        final var construction = constructionWithPhase(R1, graceEnd);

        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                HistoryProof.DEFAULT,
                weights,
                proofKeys,
                delayer,
                executor,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);

        final var outcome = subject.advance(now, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        final var failed = (HistoryProver.Outcome.Failed) outcome;
        assertTrue(failed.reason().contains("Still missing messages"));
    }

    @Test
    void advanceInitializesWrapsMessageAndPublishesR1() {
        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                null,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
        given(historyLibrary.hashAddressBook(any())).willReturn("HASH".getBytes(UTF_8));
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.runWrapsPhaseR1(any(), any(), any())).willReturn(MESSAGE_BYTES.toByteArray());
        given(submissions.submitWrapsSigningMessage(eq(R1), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        final var construction = constructionWithPhase(R1, null);
        final var outcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(Bytes.class);
        verify(submissions).submitWrapsSigningMessage(eq(R1), captor.capture(), eq(CONSTRUCTION_ID));
        assertEquals(MESSAGE_BYTES, captor.getValue());
    }

    @Test
    void advancePublishesR3WhenEligible() {
        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                null,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
        given(historyLibrary.hashAddressBook(any())).willReturn("HASH".getBytes(UTF_8));
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.runWrapsPhaseR3(any(), any(), any(), any(), any(), any()))
                .willReturn(R3_MESSAGE.toByteArray());
        given(submissions.submitWrapsSigningMessage(eq(R3), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        setField("entropy", new byte[32]);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R2_MESSAGE, R2, EPOCH),
                writableHistoryStore);

        final var construction = constructionWithPhase(R3, null);
        final var outcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(Bytes.class);
        verify(submissions).submitWrapsSigningMessage(eq(R3), captor.capture(), eq(CONSTRUCTION_ID));
        assertEquals(R3_MESSAGE, captor.getValue());
    }

    @Test
    void advancePublishesR2WhenEligible() {
        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                null,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
        given(historyLibrary.hashAddressBook(any())).willReturn("HASH".getBytes(UTF_8));
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.runWrapsPhaseR2(any(), any(), any(), any(), any())).willReturn(R2_MESSAGE.toByteArray());
        given(submissions.submitWrapsSigningMessage(eq(R2), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        setField("entropy", new byte[32]);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        final var construction = constructionWithPhase(R2, null);
        final var outcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(Bytes.class);
        verify(submissions).submitWrapsSigningMessage(eq(R2), captor.capture(), eq(CONSTRUCTION_ID));
        assertEquals(R2_MESSAGE, captor.getValue());
    }

    @Test
    void addWrapsSigningMessageRejectsWrongPhase() {
        final var publication = new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R2, EPOCH);

        assertFalse(subject.addWrapsSigningMessage(CONSTRUCTION_ID, publication, writableHistoryStore));
        verifyNoInteractions(writableHistoryStore);
    }

    @Test
    void r1PhaseAdvancesToR2WhenEnoughWeight() {
        final var first = new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH);
        final var second = new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH.plusSeconds(1));

        assertTrue(subject.addWrapsSigningMessage(CONSTRUCTION_ID, first, writableHistoryStore));
        assertTrue(subject.addWrapsSigningMessage(CONSTRUCTION_ID, second, writableHistoryStore));

        // A third R1 message from any node should be rejected since only R1 messages from two nodes are allowed
        assertFalse(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(999L, R1_MESSAGE, R1, EPOCH.plusSeconds(2)),
                writableHistoryStore));

        verify(writableHistoryStore).advanceWrapsSigningPhase(eq(CONSTRUCTION_ID), eq(R2), any());
    }

    @Test
    void duplicateR1MessagesRejected() {
        final var first = new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH);
        final var duplicate = new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH.plusSeconds(1));

        assertTrue(subject.addWrapsSigningMessage(CONSTRUCTION_ID, first, writableHistoryStore));
        assertFalse(subject.addWrapsSigningMessage(CONSTRUCTION_ID, duplicate, writableHistoryStore));
    }

    @Test
    void aggregatePhasePublishesAggregateVoteWhenWrapsDisabledOrNoSourceProof() {
        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                null,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
        given(historyLibrary.hashAddressBook(any())).willReturn("HASH".getBytes(UTF_8));
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        given(historyLibrary.verifyAggregateSignature(any(), any(), any())).willReturn(true);

        setField("entropy", new byte[32]);
        subject.replayWrapsSigningMessage(CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH));
        subject.replayWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH));

        subject.replayWrapsSigningMessage(CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, EPOCH));
        subject.replayWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(OTHER_NODE_ID, R2_MESSAGE, R2, EPOCH));

        subject.replayWrapsSigningMessage(CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R3_MESSAGE, R3, EPOCH));
        subject.replayWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(OTHER_NODE_ID, R3_MESSAGE, R3, EPOCH));

        final var construction = constructionWithPhase(AGGREGATE, null);
        final var outcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        final var proof = captor.getValue();
        final var chainOfTrust = proof.chainOfTrustProofOrThrow();
        assertTrue(chainOfTrust.hasAggregatedNodeSignatures());
    }

    @Test
    void aggregatePhasePublishesIncrementalWrapsVoteWhenSourceProofExtensible() {
        final var sourceProof = HistoryProof.newBuilder()
                .uncompressedWrapsProof(UNCOMPRESSED)
                .chainOfTrustProof(
                        ChainOfTrustProof.newBuilder().wrapsProof(COMPRESSED).build())
                .build();

        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                sourceProof,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
        given(historyLibrary.hashAddressBook(any())).willReturn("HASH".getBytes(UTF_8));
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(tssConfig.wrapsEnabled()).willReturn(true);
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        final var incremental =
                new com.hedera.cryptography.wraps.Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray());
        given(historyLibrary.constructIncrementalWrapsProof(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(incremental);
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(historyLibrary.verifyAggregateSignature(any(), any(), any())).willReturn(true);

        setField("entropy", new byte[32]);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R2_MESSAGE, R2, EPOCH),
                writableHistoryStore);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R3_MESSAGE, R3, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R3_MESSAGE, R3, EPOCH),
                writableHistoryStore);

        final var construction = constructionWithPhase(AGGREGATE, null);
        final var outcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        final var proof = captor.getValue();
        assertEquals(UNCOMPRESSED, proof.uncompressedWrapsProof());
        final var chainOfTrust = proof.chainOfTrustProofOrThrow();
        assertTrue(chainOfTrust.hasWrapsProof());
    }

    @Test
    void aggregatePhasePublishesGenesisWrapsVoteWhenSourceProofNotExtensible() {
        final var sourceProof = HistoryProof.newBuilder()
                .uncompressedWrapsProof(Bytes.EMPTY)
                .chainOfTrustProof(
                        ChainOfTrustProof.newBuilder().aggregatedNodeSignatures(AggregatedNodeSignatures.DEFAULT))
                .build();

        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                sourceProof,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
        given(historyLibrary.hashAddressBook(any())).willReturn("HASH".getBytes(UTF_8));
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(tssConfig.wrapsEnabled()).willReturn(true);
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        final var genesis =
                new com.hedera.cryptography.wraps.Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray());
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any()))
                .willReturn(genesis);
        given(historyLibrary.constructIncrementalWrapsProof(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(genesis);
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(historyLibrary.verifyAggregateSignature(any(), any(), any())).willReturn(true);

        setField("entropy", new byte[32]);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R2_MESSAGE, R2, EPOCH),
                writableHistoryStore);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R3_MESSAGE, R3, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R3_MESSAGE, R3, EPOCH),
                writableHistoryStore);

        final var construction = constructionWithPhase(AGGREGATE, null);
        final var outcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        final var proof = captor.getValue();
        assertEquals(UNCOMPRESSED, proof.uncompressedWrapsProof());
        final var chainOfTrust = proof.chainOfTrustProofOrThrow();
        assertTrue(chainOfTrust.hasWrapsProof());
    }

    @Test
    void r2PhaseRequiresR1ParticipationAndAdvancesToR3() {
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        assertFalse(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(999L, R2_MESSAGE, R2, EPOCH), writableHistoryStore));

        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, EPOCH), writableHistoryStore));
        // Second R2 from OTHER_NODE_ID is accepted and is what triggers the phase change
        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R2_MESSAGE, R2, EPOCH),
                writableHistoryStore));

        verify(writableHistoryStore).advanceWrapsSigningPhase(eq(CONSTRUCTION_ID), eq(R3), any());
    }

    @Test
    void r3PhaseRequiresR1ParticipationAndAdvancesToAggregate() {
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R2_MESSAGE, R2, EPOCH),
                writableHistoryStore);

        assertFalse(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(999L, R3_MESSAGE, R3, EPOCH), writableHistoryStore));

        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R3_MESSAGE, R3, EPOCH), writableHistoryStore));
        // Second R3 from OTHER_NODE_ID is accepted and is what triggers the phase change
        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R3_MESSAGE, R3, EPOCH),
                writableHistoryStore));

        verify(writableHistoryStore).advanceWrapsSigningPhase(eq(CONSTRUCTION_ID), eq(AGGREGATE), isNull());
    }

    @Test
    void replayWrapsSigningMessageDoesNotWriteState() {
        final var publication = new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH);

        subject.replayWrapsSigningMessage(CONSTRUCTION_ID, publication);

        verifyNoInteractions(writableHistoryStore);
    }

    @Test
    void cancelPendingWorkCancelsFutures() {
        final var r1Future = new CompletableFuture<Void>();
        final var r2Future = new CompletableFuture<Void>();
        final var r3Future = new CompletableFuture<Void>();
        final var voteFuture = new CompletableFuture<Void>();

        subject = new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                null,
                weights,
                proofKeys,
                delayer,
                Runnable::run,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());

        setField("r1Future", r1Future);
        setField("r2Future", r2Future);
        setField("r3Future", r3Future);
        setField("voteFuture", voteFuture);

        assertTrue(subject.cancelPendingWork());
        assertTrue(r1Future.isCancelled());
        assertTrue(r2Future.isCancelled());
        assertTrue(r3Future.isCancelled());
        assertTrue(voteFuture.isCancelled());
    }

    @Test
    void cancelPendingWorkReturnsFalseWhenNothingToCancel() {
        assertFalse(subject.cancelPendingWork());
    }

    @Test
    void observeProofVoteDoesNothingWhenVoteDecisionFutureIsNull() {
        final var vote =
                HistoryProofVote.newBuilder().proof(HistoryProof.DEFAULT).build();

        // voteDecisionFuture is null by default, so this should return early
        subject.observeProofVote(OTHER_NODE_ID, vote, false);

        // No exception thrown, and no interactions with submissions
        verifyNoInteractions(submissions);
    }

    @Test
    void observeProofVoteDoesNothingWhenVoteDecisionFutureIsDone() {
        final var completedFuture = CompletableFuture.completedFuture(null);
        setField("voteDecisionFuture", completedFuture);

        final var vote =
                HistoryProofVote.newBuilder().proof(HistoryProof.DEFAULT).build();

        subject.observeProofVote(OTHER_NODE_ID, vote, false);

        // No exception thrown, and no interactions with submissions
        verifyNoInteractions(submissions);
    }

    @Test
    void observeProofVoteSkipsVoteWhenProofFinalized() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        final var vote =
                HistoryProofVote.newBuilder().proof(HistoryProof.DEFAULT).build();

        subject.observeProofVote(OTHER_NODE_ID, vote, true);

        // The vote decision future should be completed
        assertTrue(pendingFuture.isDone());
    }

    @Test
    void observeProofVoteStoresHashWhenVoteHasProofButHistoryProofIsNull() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        final var proof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build();
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        subject.observeProofVote(OTHER_NODE_ID, vote, false);

        // The vote decision future should NOT be completed since historyProof is null
        assertFalse(pendingFuture.isDone());
    }

    @Test
    void observeProofVoteCompletesWithCongruentWhenProofMatches() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        // Create a proof and set it as the historyProof
        final var proof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .build();
        setField("historyProof", proof);

        // Create a vote with the same proof
        final var vote = HistoryProofVote.newBuilder().proof(proof).build();

        subject.observeProofVote(OTHER_NODE_ID, vote, false);

        // The vote decision future should be completed since the proofs match
        assertTrue(pendingFuture.isDone());
    }

    @Test
    void observeProofVoteDoesNotCompleteWhenProofDoesNotMatch() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        // Create a proof and set it as the historyProof
        final var selfProof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .uncompressedWrapsProof(Bytes.wrap("selfProofData"))
                .build();
        setField("historyProof", selfProof);

        // Create a vote with a different proof
        final var otherProof = HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.DEFAULT)
                .uncompressedWrapsProof(Bytes.wrap("otherProofData"))
                .build();
        final var vote = HistoryProofVote.newBuilder().proof(otherProof).build();

        subject.observeProofVote(OTHER_NODE_ID, vote, false);

        // The vote decision future should NOT be completed since the proofs don't match
        assertFalse(pendingFuture.isDone());
    }

    @Test
    void observeProofVoteDoesNothingWhenVoteHasNoProof() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        // Create a vote with congruent_node_id instead of proof
        final var vote = HistoryProofVote.newBuilder().congruentNodeId(999L).build();

        subject.observeProofVote(OTHER_NODE_ID, vote, false);

        // The vote decision future should NOT be completed
        assertFalse(pendingFuture.isDone());
    }

    private void setField(String name, Object value) {
        try {
            final var field = WrapsHistoryProver.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(subject, value);
        } catch (Exception e) {
            fail(e);
        }
    }
}
