// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.node.app.history.impl.ProofVoteCategory.INVALID;
import static com.hedera.node.app.history.impl.ProofVoteCategory.VALID;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Instant.EPOCH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.cryptography.wraps.Proof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final long THIRD_NODE_ID = 3L;
    private static final long OFFLINE_NODE_ID = 0L;
    private static final long CONSTRUCTION_ID = 123L;
    private static final Bytes LEDGER_ID =
            Bytes.wrap("0123456789abcdef0123456789abcdef".getBytes(UTF_8)).append(Bytes.wrap(new byte[32]));
    private static final Bytes TARGET_METADATA = Bytes.wrap("meta");
    private static final Bytes MESSAGE_BYTES = Bytes.wrap("msg");
    private static final Bytes R1_MESSAGE = Bytes.wrap("r1");
    private static final Bytes R2_MESSAGE = Bytes.wrap("r2");
    private static final Bytes R3_MESSAGE = Bytes.wrap("r3");
    private static final Bytes TARGET_BOOK_HASH = Bytes.wrap("HASH");
    private static final Bytes AGG_SIG = Bytes.wrap("aggSig");
    private static final Bytes UNCOMPRESSED = Bytes.wrap("uncompressed");
    private static final Bytes COMPRESSED = Bytes.wrap("compressed");
    private static final Bytes SAME_ROSTER_HASH = Bytes.wrap("SAME");
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(5);
    private static final Duration JITTER_PER_RANK = Duration.ofSeconds(5);

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

        subject = newSubject(null, executor, delayer);
    }

    @Test
    void advanceFailsWhenNonGenesisWithoutLedgerId() {
        subject = newSubject(wrapsExtensibleProof(), executor, delayer);

        final var outcome = subject.advance(
                EPOCH, transitionConstruction(R1, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        final var failed = assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        assertTrue(failed.reason().contains("genesis WRAPS proofs"));
        verifyNoInteractions(submissions);
    }

    @Test
    void advanceFailsWhenExtendingChainOfTrustWithoutWrapsExtensibleSourceProof() {
        subject = newSubject(HistoryProof.DEFAULT, executor, delayer);

        final var outcome = subject.advance(
                EPOCH, transitionConstruction(R1, null), TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID, true);

        final var failed = assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        assertTrue(failed.reason().startsWith(WrapsHistoryProver.NOT_WRAPS_EXTENSIBLE_FAILURE_PREFIX));
        assertFalse(WrapsHistoryProver.isRecoverableFailure(failed.reason()));
        verifyNoInteractions(historyLibrary, submissions);
    }

    @Test
    void advanceFailsWhenTargetAddressBookIsTooLargeForWrapsProof() {
        final SortedMap<Long, Long> tooManyNodes = new TreeMap<>();
        for (long nodeId = 0; nodeId <= HistoryLibrary.MAX_ADDRESS_BOOK_SIZE; nodeId++) {
            tooManyNodes.put(nodeId, 1L);
        }
        weights = new RosterTransitionWeights(sourceWeights, tooManyNodes);
        subject = newSubject(wrapsExtensibleProof(), executor, delayer);

        final var outcome = subject.advance(
                EPOCH, transitionConstruction(R1, null), TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID, true);

        final var failed = assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        assertTrue(failed.reason().startsWith(WrapsHistoryProver.ADDRESS_BOOK_TOO_LARGE_FAILURE_PREFIX));
        assertTrue(failed.reason().contains("(" + (HistoryLibrary.MAX_ADDRESS_BOOK_SIZE + 1) + " nodes"));
        assertFalse(WrapsHistoryProver.isRecoverableFailure(failed.reason()));
        verifyNoInteractions(historyLibrary, submissions);
    }

    @Test
    void advanceFailsWhenGracePeriodExpired() {
        final var now = Instant.ofEpochSecond(10);
        final var graceEnd = Instant.ofEpochSecond(5);
        final var construction = groundingConstruction(R1, graceEnd);
        // A construction grounding a chain of trust needs no WRAPS-extensible source proof
        subject = newSubject(HistoryProof.DEFAULT, executor, delayer);

        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);

        final var outcome =
                subject.advance(now, construction, TARGET_METADATA, targetProofKeys, tssConfig, LEDGER_ID, true);

        final var failed = assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        assertTrue(failed.reason().contains("Still missing messages"));
    }

    @Test
    void genesisMissingSelectedR2IsRecoverableOnlyAfterGracePeriod() {
        final var lastMessageTime = EPOCH.plusSeconds(2);
        givenWrapsMessage();

        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore));
        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH.plusSeconds(1)),
                writableHistoryStore));
        final var graceEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(writableHistoryStore).advanceWrapsSigningPhase(eq(CONSTRUCTION_ID), eq(R2), graceEndCaptor.capture());
        final var graceEnd = graceEndCaptor.getValue();
        assertEquals(EPOCH.plusSeconds(1).plus(GRACE_PERIOD), graceEnd);
        final var construction = groundingConstruction(R2, graceEnd);

        // Both R1 participants are required in R2; the other participant's R2 never arrives.
        assertTrue(subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(SELF_ID, R2_MESSAGE, R2, lastMessageTime),
                writableHistoryStore));
        assertSame(
                HistoryProver.Outcome.InProgress.INSTANCE,
                subject.advance(
                        lastMessageTime, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true));
        assertSame(
                HistoryProver.Outcome.InProgress.INSTANCE,
                subject.advance(graceEnd, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true));

        final var outcome = subject.advance(
                graceEnd.plusNanos(1), construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        final var failure = assertInstanceOf(HistoryProver.Outcome.Failed.class, outcome);
        assertEquals(
                "Still missing messages from R1 nodes [2] after end of grace period for phase R2", failure.reason());
        assertTrue(WrapsHistoryProver.isRecoverableFailure(failure.reason()));
        verify(writableHistoryStore, never()).advanceWrapsSigningPhase(eq(CONSTRUCTION_ID), eq(R3), any());
        verifyNoInteractions(submissions);
    }

    @Test
    void advanceInitializesWrapsMessageAndPublishesR1() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.runWrapsPhaseR1(any(), any(), any())).willReturn(MESSAGE_BYTES.toByteArray());
        given(submissions.submitWrapsSigningMessage(eq(R1), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(R1, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(Bytes.class);
        verify(submissions).submitWrapsSigningMessage(eq(R1), captor.capture(), eq(CONSTRUCTION_ID));
        assertEquals(MESSAGE_BYTES, captor.getValue());
    }

    @Test
    void advanceDoesNotPublishOrPoisonFutureWhenCannotSubmit() {
        subject = newSubject(null, Runnable::run, delayer);

        final var construction = groundingConstruction(R1, null);
        final var inactiveOutcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, false);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, inactiveOutcome);
        verifyNoInteractions(historyLibrary, submissions);

        givenWrapsMessage();
        given(historyLibrary.runWrapsPhaseR1(any(), any(), any())).willReturn(MESSAGE_BYTES.toByteArray());
        given(submissions.submitWrapsSigningMessage(eq(R1), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        final var activeOutcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, activeOutcome);
        final var captor = ArgumentCaptor.forClass(Bytes.class);
        verify(submissions).submitWrapsSigningMessage(eq(R1), captor.capture(), eq(CONSTRUCTION_ID));
        assertEquals(MESSAGE_BYTES, captor.getValue());
    }

    @Test
    void advanceDoesNotCachePartialWrapsStateIfHashingThrows() {
        subject = newSubject(null, Runnable::run, delayer);
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
        given(historyLibrary.hashAddressBook(any())).willThrow(new IllegalArgumentException("boom"));

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.advance(
                        EPOCH,
                        groundingConstruction(R1, null),
                        TARGET_METADATA,
                        targetProofKeys,
                        tssConfig,
                        null,
                        true));

        assertNull(getField("targetAddressBook"));
        assertNull(getField("wrapsMessage"));
        assertNull(getField("targetAddressBookHash"));
        verifyNoInteractions(submissions);
    }

    @Test
    void advancePublishesR3WhenEligible() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.runWrapsPhaseR3(any(), any(), any(), any(), any(), any(), any()))
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

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(R3, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        final var captor = ArgumentCaptor.forClass(Bytes.class);
        verify(submissions).submitWrapsSigningMessage(eq(R3), captor.capture(), eq(CONSTRUCTION_ID));
        assertEquals(R3_MESSAGE, captor.getValue());
    }

    @Test
    void advancePublishesR2WhenEligible() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.runWrapsPhaseR2(any(), any(), any(), any(), any(), any()))
                .willReturn(R2_MESSAGE.toByteArray());
        given(submissions.submitWrapsSigningMessage(eq(R2), any(), eq(CONSTRUCTION_ID)))
                .willReturn(CompletableFuture.completedFuture(null));

        setField("entropy", new byte[32]);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID, new WrapsMessagePublication(SELF_ID, R1_MESSAGE, R1, EPOCH), writableHistoryStore);
        subject.addWrapsSigningMessage(
                CONSTRUCTION_ID,
                new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH),
                writableHistoryStore);

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(R2, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

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
    void addWrapsSigningMessageIgnoresNodeWithMissingSourceSchnorrKey() {
        proofKeys.put(OTHER_NODE_ID, HistoryLibrary.MISSING_SCHNORR_KEY);
        subject = newSubject(null, executor, delayer);

        final var publication = new WrapsMessagePublication(OTHER_NODE_ID, R1_MESSAGE, R1, EPOCH);

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
    void aggregatePhaseVotesForGenesisProofOfTheSignedLedgerId() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        // The genesis proof is anchored in the hash of the very address book that signed its ledger id
        verify(historyLibrary)
                .constructGenesisWrapsProof(
                        aryEq(TARGET_BOOK_HASH.toByteArray()),
                        aryEq(TARGET_METADATA.toByteArray()),
                        aryEq(AGG_SIG.toByteArray()),
                        eq(Set.of(SELF_ID, OTHER_NODE_ID)),
                        any());
        verify(historyLibrary, never()).constructIncrementalWrapsProof(any(), any(), any(), any(), any(), any(), any());
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        final var proof = captor.getValue();
        assertEquals(COMPRESSED, proof.chainOfTrustProofOrThrow().wrapsProofOrThrow());
        assertEquals(UNCOMPRESSED, proof.uncompressedWrapsProof());
        assertEquals(new History(TARGET_BOOK_HASH, TARGET_METADATA), proof.targetHistory());
        assertEquals(
                List.of(new ProofKey(SELF_ID, Bytes.wrap("pk1")), new ProofKey(OTHER_NODE_ID, Bytes.wrap("pk2"))),
                proof.targetProofKeys());
    }

    @Test
    void aggregatePhaseGroundsAFreshGenesisProofEvenWithAnExtensibleSourceProof() {
        subject = newSubject(wrapsExtensibleProof(), Runnable::run, delayer);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        // A fresh genesis proof for the current roster is built by a construction with that roster on both sides
        final var outcome = subject.advance(
                EPOCH,
                groundingConstruction(AGGREGATE, null),
                TARGET_METADATA,
                targetProofKeys,
                tssConfig,
                LEDGER_ID,
                true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        verify(historyLibrary, never()).constructIncrementalWrapsProof(any(), any(), any(), any(), any(), any(), any());
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        assertTrue(captor.getValue().chainOfTrustProofOrThrow().hasWrapsProof());
    }

    @Test
    void aggregatePhaseVotesForIncrementalProofFoldedOntoSourceProof() {
        final var sourceProof = wrapsExtensibleProof();
        subject = newSubject(sourceProof, Runnable::run, delayer);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructIncrementalWrapsProof(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new Proof(Bytes.wrap("next-uncompressed").toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        final var outcome = subject.advance(
                EPOCH,
                transitionConstruction(AGGREGATE, null),
                TARGET_METADATA,
                targetProofKeys,
                tssConfig,
                LEDGER_ID,
                true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        // The incremental proof is anchored in the genesis address book hash at the front of the ledger id
        verify(historyLibrary)
                .constructIncrementalWrapsProof(
                        aryEq(LEDGER_ID
                                .slice(0, HistoryLibrary.ADDRESS_BOOK_HASH_LENGTH)
                                .toByteArray()),
                        aryEq(UNCOMPRESSED.toByteArray()),
                        any(),
                        any(),
                        aryEq(TARGET_METADATA.toByteArray()),
                        aryEq(AGG_SIG.toByteArray()),
                        eq(Set.of(SELF_ID, OTHER_NODE_ID)));
        verify(historyLibrary, never()).constructGenesisWrapsProof(any(), any(), any(), any(), any());
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        assertEquals(Bytes.wrap("next-uncompressed"), captor.getValue().uncompressedWrapsProof());
    }

    @Test
    void aggregatePhaseSkipsVoteWhenAggregationReturnsNull() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any(), any()))
                .willReturn(null);
        replaySigningRounds();

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        verify(historyLibrary, never()).constructGenesisWrapsProof(any(), any(), any(), any(), any());
        verifyNoInteractions(submissions);
    }

    @Test
    void aggregatePhaseSkipsVoteWhenAggregateSignatureIsInvalid() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(historyLibrary.verifyAggregateSignature(any(), any(), any(), any(), any()))
                .willReturn(false);
        replaySigningRounds();

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        verify(historyLibrary, never()).constructGenesisWrapsProof(any(), any(), any(), any(), any());
        verifyNoInteractions(submissions);
    }

    @Test
    void aggregatePhaseSkipsVoteWhenProofConstructionReturnsNull() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(null);
        replaySigningRounds();

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        verifyNoInteractions(submissions);
        assertNull(getField("phaseNeedingWrapsReadinessRetry"));
    }

    @Test
    void aggregatePhaseDefersUntilWrapsLibraryIsReady() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        // Not ready on the first advance(); ready on the second (twice, once more inside the proof computation)
        given(historyLibrary.wrapsProverReady()).willReturn(false, true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(historyLibrary.verifyAggregateSignature(any(), any(), any(), any(), any()))
                .willReturn(true);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();
        final var construction = groundingConstruction(AGGREGATE, null);

        final var firstOutcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, firstOutcome);
        verifyNoInteractions(submissions);
        verify(historyLibrary, never()).runAggregationPhase(any(), any(), any(), any(), any(), any());
        assertSame(
                AGGREGATE,
                getField("phaseNeedingWrapsReadinessRetry"),
                "Deferring because WRAPS is not ready must flag the phase so the next round retries");

        final var secondOutcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, secondOutcome);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), any());
        assertNull(getField("phaseNeedingWrapsReadinessRetry"));
    }

    @Test
    void aggregatePhaseFlagsRetryWhenProofComputationFindsWrapsNotReady() {
        // Covers the window where wrapsProverReady() flips to false between the publishIfNeeded guard and the
        // proof computation; the noop must flag the phase so the next advance() clears the stale vote future
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true, false, true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(historyLibrary.verifyAggregateSignature(any(), any(), any(), any(), any()))
                .willReturn(true);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();
        final var construction = groundingConstruction(AGGREGATE, null);

        final var firstOutcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, firstOutcome);
        verifyNoInteractions(submissions);
        assertSame(AGGREGATE, getField("phaseNeedingWrapsReadinessRetry"));

        final var secondOutcome =
                subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, secondOutcome);
        final var captor = ArgumentCaptor.forClass(HistoryProof.class);
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), captor.capture());
        assertTrue(captor.getValue().chainOfTrustProofOrThrow().hasWrapsProof());
    }

    @Test
    void aggregatePhaseKeepsRetryFlagAcrossMultipleStillNotReadyRounds() {
        subject = newSubject(null, Runnable::run, delayer);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(false);
        replaySigningRounds();
        final var construction = groundingConstruction(AGGREGATE, null);

        for (int i = 0; i < 3; i++) {
            final var outcome =
                    subject.advance(EPOCH, construction, TARGET_METADATA, targetProofKeys, tssConfig, null, true);
            assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
            assertSame(
                    AGGREGATE,
                    getField("phaseNeedingWrapsReadinessRetry"),
                    "Iteration " + i + ": phase must remain flagged while WRAPS is not ready");
        }
        verifyNoInteractions(submissions);
        verify(historyLibrary, never()).constructGenesisWrapsProof(any(), any(), any(), any(), any());
    }

    @Test
    void nodeThatPublishedNoR1MessageRanksAfterNodesThatDid() {
        // With three equally weighted source nodes, construction #123 would rotate this node to the top; but it
        // published no R1 message, so it ranks after both nodes that did
        givenThreeNodeNetwork(Map.of(SELF_ID, 1L, OTHER_NODE_ID, 1L, THIRD_NODE_ID, 1L));
        final List<Long> delays = new ArrayList<>();
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> {
            delays.add(delay);
            return delayedExecutor;
        });
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        replaySigningRoundsFrom(List.of(OTHER_NODE_ID, THIRD_NODE_ID));

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertEquals(List.of(2 * JITTER_PER_RANK.toMillis()), delays);
        assertEquals(1, delayedExecutor.pendingTasks());
        verify(historyLibrary, never()).runAggregationPhase(any(), any(), any(), any(), any(), any());
    }

    @Test
    void offlineNodeNeverLeadsProof() {
        // Construction #123 would rotate node0 to the top of nodes {0, 1, 2}; but node0 published no R1 message,
        // so this node, the only one that did, leads and computes its proof without waiting
        givenThreeNodeNetwork(Map.of(OFFLINE_NODE_ID, 1L, SELF_ID, 3L, OTHER_NODE_ID, 1L));
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> {
            throw new AssertionError("Top-ranked node should not wait " + delay + "ms");
        });
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRoundsFrom(List.of(SELF_ID));

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        verify(historyLibrary).constructGenesisWrapsProof(any(), any(), any(), any(), any());
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), any());
    }

    @Test
    void lowerRankedNodeComputesProofOnlyAfterItsJitter() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> {
            // Both nodes published R1 messages, and construction #123 makes node2 the top-ranked node and this
            // node next
            assertEquals(JITTER_PER_RANK.toMillis(), delay);
            return delayedExecutor;
        });
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        verify(historyLibrary, never()).runAggregationPhase(any(), any(), any(), any(), any(), any());
        assertEquals(1, delayedExecutor.pendingTasks());

        delayedExecutor.runNext();

        verify(historyLibrary).constructGenesisWrapsProof(any(), any(), any(), any(), any());
        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), any());
    }

    @Test
    void lowerRankedNodeVotesCongruentWithoutComputingWhenValidProofArrivesDuringJitter() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> delayedExecutor);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(submissions.submitCongruentProofVote(CONSTRUCTION_ID, OTHER_NODE_ID))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), false, VALID);

        verify(submissions).submitCongruentProofVote(CONSTRUCTION_ID, OTHER_NODE_ID);
        // Once the jitter elapses there is nothing left to do
        delayedExecutor.runNext();
        verify(historyLibrary, never()).runAggregationPhase(any(), any(), any(), any(), any(), any());
        verify(historyLibrary, never()).constructGenesisWrapsProof(any(), any(), any(), any(), any());
        verify(submissions, never()).submitExplicitProofVote(anyLong(), any());
    }

    @Test
    void invalidProofArrivingDuringJitterDoesNotPreemptOwnProof() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> delayedExecutor);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        given(submissions.submitExplicitProofVote(eq(CONSTRUCTION_ID), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), false, INVALID);
        delayedExecutor.runNext();

        verify(submissions).submitExplicitProofVote(eq(CONSTRUCTION_ID), any());
        verify(submissions, never()).submitCongruentProofVote(anyLong(), anyLong());
    }

    @Test
    void validProofObservedBeforeSchedulingCausesImmediateCongruentVote() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> delayedExecutor);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(submissions.submitCongruentProofVote(CONSTRUCTION_ID, OTHER_NODE_ID))
                .willReturn(CompletableFuture.completedFuture(null));
        replaySigningRounds();

        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), false, VALID);
        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        verify(submissions).submitCongruentProofVote(CONSTRUCTION_ID, OTHER_NODE_ID);
        assertEquals(0, delayedExecutor.pendingTasks());
        verify(historyLibrary, never()).runAggregationPhase(any(), any(), any(), any(), any(), any());
    }

    @Test
    void finalizedProofObservedBeforeSchedulingPreventsVote() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> delayedExecutor);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        replaySigningRounds();

        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), true, VALID);
        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        verifyNoInteractions(submissions);
        assertEquals(0, delayedExecutor.pendingTasks());
    }

    @Test
    void finalizedProofObservedDuringJitterSkipsComputationAndVote() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> delayedExecutor);
        givenWrapsMessage();
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        replaySigningRounds();

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), true, VALID);
        delayedExecutor.runNext();

        verifyNoInteractions(submissions);
        verify(historyLibrary, never()).runAggregationPhase(any(), any(), any(), any(), any(), any());
    }

    @Test
    void validProofObservedWhileComputingOwnProofStillLeadsToCongruentVote() {
        final var delayedExecutor = new ManualExecutor();
        subject = newSubject(null, Runnable::run, (delay, unit, executor) -> delayedExecutor);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(submissions.submitCongruentProofVote(CONSTRUCTION_ID, OTHER_NODE_ID))
                .willReturn(CompletableFuture.completedFuture(null));
        // Observe the other node's valid proof while this node is still computing its own
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willAnswer(invocation -> {
                    subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), false, VALID);
                    return new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray());
                });
        replaySigningRounds();

        subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);
        delayedExecutor.runNext();

        verify(submissions).submitCongruentProofVote(CONSTRUCTION_ID, OTHER_NODE_ID);
        verify(submissions, never()).submitExplicitProofVote(anyLong(), any());
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

        subject = newSubject(null, Runnable::run, delayer);

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
    void observeProofVoteDoesNotSubmitWhenVoteDecisionFutureIsNull() {
        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), false, VALID);

        verifyNoInteractions(submissions);
    }

    @Test
    void observeProofVoteDoesNotSubmitWhenVoteDecisionFutureIsDone() {
        setField("voteDecisionFuture", CompletableFuture.completedFuture(null));

        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), false, VALID);

        verifyNoInteractions(submissions);
    }

    @Test
    void observeProofVoteSkipsVoteWhenProofFinalized() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        subject.observeProofVote(OTHER_NODE_ID, explicitVote(wrapsExtensibleProof()), true, INVALID);

        assertTrue(pendingFuture.isDone());
        assertNull(getField("voteDecisionFuture"));
    }

    @Test
    void observeProofVoteIgnoresInvalidExplicitVote() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        subject.observeProofVote(OTHER_NODE_ID, explicitVote(HistoryProof.DEFAULT), false, INVALID);

        assertFalse(pendingFuture.isDone());
        assertNull(getField("validProofNodeId"));
    }

    @Test
    void observeProofVoteIgnoresCongruentVote() {
        final var pendingFuture = new CompletableFuture<>();
        setField("voteDecisionFuture", pendingFuture);

        final var vote = HistoryProofVote.newBuilder().congruentNodeId(999L).build();
        subject.observeProofVote(OTHER_NODE_ID, vote, false, VALID);

        assertFalse(pendingFuture.isDone());
    }

    @Test
    void canceledConstructionSkipsMessagePublicationAfterOutputResolves() {
        final var manualExecutor = new ManualExecutor();
        subject = newSubject(null, manualExecutor, delayer);
        givenWrapsMessage();
        given(historyLibrary.runWrapsPhaseR1(any(), any(), any())).willReturn(MESSAGE_BYTES.toByteArray());

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(R1, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        manualExecutor.runNext();
        assertEquals(1, manualExecutor.pendingTasks());

        assertTrue(subject.cancelPendingWork());
        manualExecutor.runNext();

        verifyNoInteractions(submissions);
    }

    @Test
    void canceledConstructionSkipsVoteAfterProofIsComputed() {
        final var manualExecutor = new ManualExecutor();
        subject = newSubject(null, manualExecutor, delayer);
        givenWrapsMessage();
        givenValidAggregateSignature();
        given(tssConfig.wrapsVoteJitterPerRank()).willReturn(JITTER_PER_RANK);
        given(historyLibrary.constructGenesisWrapsProof(any(), any(), any(), any(), any()))
                .willReturn(new Proof(UNCOMPRESSED.toByteArray(), COMPRESSED.toByteArray()));
        replaySigningRounds();

        final var outcome = subject.advance(
                EPOCH, groundingConstruction(AGGREGATE, null), TARGET_METADATA, targetProofKeys, tssConfig, null, true);

        assertSame(HistoryProver.Outcome.InProgress.INSTANCE, outcome);
        // Compute the proof...
        manualExecutor.runNext();
        assertEquals(1, manualExecutor.pendingTasks());
        // ...but cancel the construction before acting on it
        assertTrue(subject.cancelPendingWork());
        manualExecutor.runNext();

        verifyNoInteractions(submissions);
    }

    private WrapsHistoryProver newSubject(
            @Nullable final HistoryProof sourceProof,
            final Executor executor,
            final WrapsHistoryProver.Delayer delayer) {
        return new WrapsHistoryProver(
                SELF_ID,
                GRACE_PERIOD,
                KEY_PAIR,
                sourceProof,
                weights,
                proofKeys,
                delayer,
                executor,
                historyLibrary,
                submissions,
                new WrapsMpcStateMachine());
    }

    private void givenWrapsMessage() {
        given(historyLibrary.hashAddressBook(any())).willReturn(TARGET_BOOK_HASH.toByteArray());
        given(historyLibrary.computeWrapsMessage(any(), any())).willReturn("MSG".getBytes(UTF_8));
    }

    private void givenValidAggregateSignature() {
        given(historyLibrary.wrapsProverReady()).willReturn(true);
        given(historyLibrary.runAggregationPhase(any(), any(), any(), any(), any(), any()))
                .willReturn(AGG_SIG.toByteArray());
        given(historyLibrary.verifyAggregateSignature(any(), any(), any(), any(), any()))
                .willReturn(true);
    }

    private void replaySigningRounds() {
        replaySigningRoundsFrom(List.of(SELF_ID, OTHER_NODE_ID));
    }

    private void replaySigningRoundsFrom(final List<Long> nodeIds) {
        setField("entropy", new byte[32]);
        for (final var phaseAndMessage :
                List.of(Map.entry(R1, R1_MESSAGE), Map.entry(R2, R2_MESSAGE), Map.entry(R3, R3_MESSAGE))) {
            for (final long nodeId : nodeIds) {
                subject.replayWrapsSigningMessage(
                        CONSTRUCTION_ID,
                        new WrapsMessagePublication(
                                nodeId, phaseAndMessage.getValue(), phaseAndMessage.getKey(), EPOCH));
            }
        }
    }

    /**
     * Replaces the two-node network with one of the given node weights, the same in source and target, where every
     * node has a proof key.
     */
    private void givenThreeNodeNetwork(final Map<Long, Long> nodeWeights) {
        sourceWeights.clear();
        targetWeights.clear();
        sourceWeights.putAll(nodeWeights);
        targetWeights.putAll(nodeWeights);
        nodeWeights.keySet().forEach(nodeId -> proofKeys.put(nodeId, Bytes.wrap("pk" + nodeId)));
        targetProofKeys.clear();
        targetProofKeys.putAll(proofKeys);
        weights = new RosterTransitionWeights(sourceWeights, targetWeights);
    }

    /**
     * A construction with the same roster as source and target, which grounds a chain of trust.
     */
    private static HistoryProofConstruction groundingConstruction(
            final WrapsPhase phase, @Nullable final Instant graceEnd) {
        return constructionWithPhase(phase, graceEnd)
                .copyBuilder()
                .sourceRosterHash(SAME_ROSTER_HASH)
                .targetRosterHash(SAME_ROSTER_HASH)
                .build();
    }

    /**
     * A construction with different source and target rosters, which extends a chain of trust.
     */
    private static HistoryProofConstruction transitionConstruction(
            final WrapsPhase phase, @Nullable final Instant graceEnd) {
        return constructionWithPhase(phase, graceEnd)
                .copyBuilder()
                .sourceRosterHash(Bytes.wrap("SOURCE"))
                .targetRosterHash(Bytes.wrap("TARGET"))
                .build();
    }

    private static HistoryProofConstruction constructionWithPhase(
            final WrapsPhase phase, @Nullable final Instant graceEnd) {
        final var stateBuilder = WrapsSigningState.newBuilder().phase(phase);
        if (graceEnd != null) {
            stateBuilder.gracePeriodEndTime(new Timestamp(graceEnd.getEpochSecond(), graceEnd.getNano()));
        }
        return HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .wrapsSigningState(stateBuilder.build())
                .build();
    }

    private static HistoryProof wrapsExtensibleProof() {
        return HistoryProof.newBuilder()
                .chainOfTrustProof(
                        ChainOfTrustProof.newBuilder().wrapsProof(COMPRESSED).build())
                .uncompressedWrapsProof(UNCOMPRESSED)
                .build();
    }

    private static HistoryProofVote explicitVote(final HistoryProof proof) {
        return HistoryProofVote.newBuilder().proof(proof).build();
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

    private Object getField(String name) {
        try {
            final var field = WrapsHistoryProver.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(subject);
        } catch (Exception e) {
            fail(e);
            return null;
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(final Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            final var task = tasks.poll();
            assertNotNull(task);
            task.run();
        }

        int pendingTasks() {
            return tasks.size();
        }
    }
}
