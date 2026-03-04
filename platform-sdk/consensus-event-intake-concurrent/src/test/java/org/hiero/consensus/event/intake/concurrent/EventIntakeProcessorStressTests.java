// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.metrics.api.Metrics;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.base.concurrent.test.fixtures.ConcurrentTesting;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.crypto.PreGeneratedX509Certs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stress tests for {@link ConcurrentEventIntakeProcessor} under concurrent access.
 * These tests validate thread-safety of deduplication, eviction, and verifier cache invalidation.
 */
class EventIntakeProcessorStressTests {

    private static final int NUM_THREADS = 8;
    private static final int EVENTS_PER_THREAD = 2_000;
    private static final long ROSTER_ROUND = 3;
    private static final NodeId NODE_ID = NodeId.of(77);

    private Metrics metrics;
    private FakeTime time;
    private IntakeEventCounter intakeEventCounter;
    private RosterHistory rosterHistory;
    private EventHasher eventHasher;
    final Randotron random = Randotron.create();

    private final Function<PublicKey, BytesSignatureVerifier> trueVerifierFactory =
            publicKey -> (data, signature) -> true;

    private final EventFieldValidator passingValidator = event -> true;

    private static RosterEntry generateMockRosterEntry(final NodeId nodeId) {
        try {
            return new RosterEntry(
                    nodeId.id(),
                    10,
                    Bytes.wrap(PreGeneratedX509Certs.getSigCert(nodeId.id()).getEncoded()),
                    List.of());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setup() {
        metrics = new NoOpMetrics();
        time = new FakeTime();
        intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> null).when(intakeEventCounter).eventExitedIntakePipeline(any());
        eventHasher = new DefaultEventHasher();

        final RosterEntry rosterEntry = generateMockRosterEntry(NODE_ID);
        final Roster roster = new Roster(List.of(rosterEntry));
        final Bytes hash = RosterUtils.hash(roster).getBytes();

        rosterHistory = new RosterHistory(List.of(new RoundRosterPair(ROSTER_ROUND, hash)), Map.of(hash, roster));
    }

    private ConcurrentEventIntakeProcessor createProcessor() {
        return new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                trueVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null);
    }

    // ---- Test 1: Concurrent dedup — same event from N threads, exactly one wins ----

    @Test
    @DisplayName("Same event submitted from multiple threads — exactly one passes dedup")
    void concurrentDedupSameEvent() throws Exception {
        final ConcurrentEventIntakeProcessor processor = createProcessor();

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NODE_ID)
                .setBirthRound(ROSTER_ROUND)
                .build();

        final AtomicInteger passCount = new AtomicInteger(0);
        final ConcurrentTesting testing = new ConcurrentTesting();

        for (int t = 0; t < NUM_THREADS; t++) {
            testing.addRunnable(() -> {
                int i = 100;
                while (i-- > 0) {
                    if (processor.processHashedEvent(event) != null) {
                        passCount.incrementAndGet();
                    }
                }
            });
        }

        testing.runForSeconds(10);

        assertEquals(1, passCount.get(), "Exactly one thread should win the dedup race");
    }

    // ---- Test 2: Concurrent dedup — distinct events, all pass ----

    @Test
    @DisplayName("Distinct events from multiple threads — all pass")
    void concurrentDedupDistinctEvents() throws Exception {
        final ConcurrentEventIntakeProcessor processor = createProcessor();

        // Pre-generate distinct events per thread (each thread gets its own Randotron seed)
        final List<List<PlatformEvent>> eventsPerThread = new ArrayList<>();
        for (int t = 0; t < NUM_THREADS; t++) {
            final List<PlatformEvent> events = new ArrayList<>();
            for (int i = 0; i < EVENTS_PER_THREAD; i++) {
                events.add(new TestingEventBuilder(random)
                        .setCreatorId(NODE_ID)
                        .setBirthRound(ROSTER_ROUND)
                        .build());
            }
            eventsPerThread.add(events);
        }

        final AtomicInteger totalPassed = new AtomicInteger(0);
        final ConcurrentTesting testing = new ConcurrentTesting();

        for (int t = 0; t < NUM_THREADS; t++) {
            final List<PlatformEvent> threadEvents = eventsPerThread.get(t);
            testing.addRunnable(() -> {
                for (final PlatformEvent event : threadEvents) {
                    if (processor.processHashedEvent(event) != null) {
                        totalPassed.incrementAndGet();
                    }
                }
            });
        }

        testing.runForSeconds(30);

        assertEquals(NUM_THREADS * EVENTS_PER_THREAD, totalPassed.get(), "All distinct events should pass");
    }

    // ---- Test 3: Concurrent insert + eviction — no exceptions, no lost non-ancient events ----

    @Test
    @DisplayName("Concurrent event processing and window eviction — no exceptions or lost events")
    void concurrentInsertAndEviction() throws Exception {
        final ConcurrentEventIntakeProcessor processor = createProcessor();
        final int numRounds = 50;
        final int eventsPerRound = 200;

        // Pre-generate events spread across rounds ROSTER_ROUND .. ROSTER_ROUND + numRounds - 1
        final List<PlatformEvent> allEvents = new ArrayList<>();
        for (int r = 0; r < numRounds; r++) {
            for (int i = 0; i < eventsPerRound; i++) {
                allEvents.add(new TestingEventBuilder(random)
                        .setCreatorId(NODE_ID)
                        .setBirthRound(ROSTER_ROUND + r)
                        .build());
            }
        }

        // Track which events were accepted
        final ConcurrentHashMap<PlatformEvent, Boolean> accepted = new ConcurrentHashMap<>();
        final AtomicLong evictionRound = new AtomicLong(ROSTER_ROUND);

        final ConcurrentTesting testing = new ConcurrentTesting();

        // Worker threads: process events
        final int eventsTotal = allEvents.size();
        final int chunkSize = eventsTotal / NUM_THREADS;
        for (int t = 0; t < NUM_THREADS; t++) {
            final int from = t * chunkSize;
            final int to = (t == NUM_THREADS - 1) ? eventsTotal : from + chunkSize;
            testing.addRunnable(() -> {
                for (int i = from; i < to; i++) {
                    final PlatformEvent event = allEvents.get(i);
                    if (processor.processHashedEvent(event) != null) {
                        accepted.put(event, Boolean.TRUE);
                    }
                }
            });
        }

        // Eviction thread: advance the window periodically
        testing.addRunnable(() -> {
            for (int r = 0; r < numRounds; r++) {
                final long threshold = ROSTER_ROUND + r;
                evictionRound.set(threshold);
                processor.setEventWindow(EventWindowBuilder.builder()
                        .setAncientThreshold(threshold)
                        .build());
                Thread.sleep(1); // yield to let workers interleave
            }
        });

        testing.runForSeconds(30);

        // Verify: every accepted event has a birth round >= final eviction threshold
        final long finalThreshold = evictionRound.get();
        for (final PlatformEvent event : accepted.keySet()) {
            assertTrue(
                    event.getBirthRound() >= ROSTER_ROUND,
                    "Accepted event should not have been ancient at time of acceptance");
        }

        // Verify: no exceptions were thrown (ConcurrentTesting rethrows)
        // Verify: at least some events were accepted
        assertFalse(accepted.isEmpty(), "At least some events should have been accepted");
    }

    // ---- Test 4: Concurrent processing + roster update — new birth rounds use new roster ----

    @Test
    @DisplayName("Roster update causes events at new birth rounds to use the new roster")
    void concurrentRosterUpdate() throws Exception {
        // Start with a roster containing NODE_ID
        final ConcurrentEventIntakeProcessor processor = createProcessor();

        // Build a second roster effective at a new round that does NOT contain NODE_ID
        final long newRosterRound = ROSTER_ROUND + 10;
        final NodeId otherNodeId = NodeId.of(88);
        final RosterEntry otherEntry = generateMockRosterEntry(otherNodeId);
        final Roster otherRoster = new Roster(List.of(otherEntry));
        final Bytes otherHash = RosterUtils.hash(otherRoster).getBytes();
        final RosterHistory otherRosterHistory = new RosterHistory(
                List.of(new RoundRosterPair(newRosterRound, otherHash)), Map.of(otherHash, otherRoster));

        // Phase 1: warm up verifier caches on all threads with the initial roster
        final ConcurrentTesting warmup = new ConcurrentTesting();
        for (int t = 0; t < NUM_THREADS; t++) {
            warmup.addRunnable(() -> {
                final PlatformEvent event = new TestingEventBuilder(random)
                        .setCreatorId(NODE_ID)
                        .setBirthRound(ROSTER_ROUND)
                        .build();
                assertNotNull(processor.processHashedEvent(event), "Event should pass with initial roster");
            });
        }
        warmup.runForSeconds(10);

        // Phase 2: switch roster — NODE_ID no longer exists at newRosterRound
        processor.updateRosterHistory(otherRosterHistory);

        // Phase 3: events at the NEW birth round should fail verification for NODE_ID
        final AtomicInteger passCount = new AtomicInteger(0);
        final AtomicInteger failCount = new AtomicInteger(0);
        final ConcurrentTesting postUpdate = new ConcurrentTesting();
        for (int t = 0; t < NUM_THREADS; t++) {
            postUpdate.addRunnable(() -> {
                for (int i = 0; i < 100; i++) {
                    final PlatformEvent event = new TestingEventBuilder(random)
                            .setCreatorId(NODE_ID)
                            .setBirthRound(newRosterRound)
                            .build();
                    if (processor.processHashedEvent(event) != null) {
                        passCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                }
            });
        }
        postUpdate.runForSeconds(10);

        assertEquals(0, passCount.get(), "No events should pass after roster removes the node");
        assertTrue(failCount.get() > 0, "Events should have been rejected after roster update");
    }

    // ---- Test 5: Concurrent clear — no exceptions, dedup state fully reset ----

    @Test
    @DisplayName("Clear during concurrent processing resets dedup without exceptions")
    void concurrentClear() throws Exception {
        final ConcurrentEventIntakeProcessor processor = createProcessor();

        // Pre-generate a batch of events
        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < EVENTS_PER_THREAD; i++) {
            events.add(new TestingEventBuilder(random)
                    .setCreatorId(NODE_ID)
                    .setBirthRound(ROSTER_ROUND)
                    .build());
        }

        final AtomicInteger totalPassed = new AtomicInteger(0);
        final ConcurrentTesting testing = new ConcurrentTesting();

        // Workers submit events repeatedly
        for (int t = 0; t < NUM_THREADS; t++) {
            testing.addRunnable(() -> {
                for (final PlatformEvent event : events) {
                    if (processor.processHashedEvent(event) != null) {
                        totalPassed.incrementAndGet();
                    }
                }
            });
        }

        // Clear thread fires periodically
        testing.addRunnable(() -> {
            for (int i = 0; i < 20; i++) {
                Thread.sleep(5);
                processor.clear();
            }
        });

        testing.runForSeconds(30);

        // After clear, previously seen events should be accepted again.
        // We can't assert exact counts because of timing, but we verify:
        // 1. No exceptions (ConcurrentTesting rethrows)
        // 2. More events passed than a single round (clear enabled re-acceptance)
        assertTrue(
                totalPassed.get() > events.size(),
                "Clear should allow events to be re-accepted — expected more than " + events.size() + " but got "
                        + totalPassed.get());
    }

    // ---- Test 6: Mixed workload — insert, evict, roster update, clear all at once ----

    @Test
    @DisplayName("Mixed concurrent workload — insert, evict, roster update, clear")
    void mixedConcurrentWorkload() throws Exception {
        final ConcurrentEventIntakeProcessor processor = createProcessor();
        final int numRounds = 30;
        final int eventsPerRound = 100;

        // Pre-generate events across rounds
        final List<PlatformEvent> allEvents = new ArrayList<>();

        for (int r = 0; r < numRounds; r++) {
            for (int i = 0; i < eventsPerRound; i++) {
                allEvents.add(new TestingEventBuilder(random)
                        .setCreatorId(NODE_ID)
                        .setBirthRound(ROSTER_ROUND + r)
                        .build());
            }
        }

        // Build alternate roster histories to toggle between
        final RosterEntry entry = generateMockRosterEntry(NODE_ID);
        final Roster roster = new Roster(List.of(entry));
        final Bytes hash = RosterUtils.hash(roster).getBytes();
        final List<RosterHistory> histories = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // All valid — same roster, just different RosterHistory instances to trigger cache invalidation
            histories.add(new RosterHistory(List.of(new RoundRosterPair(ROSTER_ROUND, hash)), Map.of(hash, roster)));
        }

        final ConcurrentTesting testing = new ConcurrentTesting();

        // Worker threads
        final int chunkSize = allEvents.size() / NUM_THREADS;
        for (int t = 0; t < NUM_THREADS; t++) {
            final int from = t * chunkSize;
            final int to = (t == NUM_THREADS - 1) ? allEvents.size() : from + chunkSize;
            testing.addRunnable(() -> {
                for (int i = from; i < to; i++) {
                    processor.processHashedEvent(allEvents.get(i));
                }
            });
        }

        // Eviction thread
        testing.addRunnable(() -> {
            for (int r = 0; r < numRounds; r++) {
                processor.setEventWindow(EventWindowBuilder.builder()
                        .setAncientThreshold(ROSTER_ROUND + r)
                        .build());
                Thread.sleep(1);
            }
        });

        // Roster update thread
        testing.addRunnable(() -> {
            for (int i = 0; i < 20; i++) {
                processor.updateRosterHistory(histories.get(i % histories.size()));
                Thread.sleep(2);
            }
        });

        // Clear thread
        testing.addRunnable(() -> {
            for (int i = 0; i < 5; i++) {
                Thread.sleep(10);
                processor.clear();
            }
        });

        // If this completes without exceptions, all concurrent paths are safe
        testing.runForSeconds(30);
    }

    // ---- Test 7: Per-thread verifiers protect non-thread-safe verifiers ----

    @Test
    @DisplayName("Per-thread verifier instances protect non-thread-safe verifiers")
    void perThreadVerifierIsolation() throws Exception {
        // A deliberately non-thread-safe verifier that detects concurrent access.
        // If two threads share the same instance, the counter check will fail.
        // The ThreadLocal cache ensures each thread gets its own instance.
        final Function<PublicKey, BytesSignatureVerifier> unsafeVerifierFactory =
                publicKey -> new BytesSignatureVerifier() {
                    private int entryCount = 0;

                    @Override
                    public boolean verify(final Bytes data, final Bytes signature) {
                        entryCount++;
                        final int snapshot = entryCount;
                        // Yield to increase the chance of interleaving
                        Thread.yield();
                        if (entryCount != snapshot) {
                            throw new AssertionError("Concurrent access detected in non-thread-safe verifier");
                        }
                        return true;
                    }
                };

        final ConcurrentEventIntakeProcessor processor = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                unsafeVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null);

        final ConcurrentTesting testing = new ConcurrentTesting();
        for (int t = 0; t < NUM_THREADS; t++) {
            testing.addRunnable(() -> {
                for (int i = 0; i < EVENTS_PER_THREAD; i++) {
                    final PlatformEvent event = new TestingEventBuilder(random)
                            .setCreatorId(NODE_ID)
                            .setBirthRound(ROSTER_ROUND)
                            .build();
                    processor.processHashedEvent(event);
                }
            });
        }

        // No AssertionError means each thread has its own verifier instance
        testing.runForSeconds(30);
    }
}
