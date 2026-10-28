// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.otter.fixtures.falcon.recording.Step.CreationAttempt;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
import org.hiero.otter.fixtures.falcon.recording.Step.PlatformStatusChange;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("FalconRecorder Test")
class FalconRecorderTest {

    private static final long RANDOM_SEED = 42L;

    @Test
    @DisplayName("The same spec gives the same recording")
    void sameSpecGivesSameRecording() {
        final RecordingSpec spec = RecordingSpec.builder(4, RANDOM_SEED, 2_000).build();

        final Recording first = FalconRecorder.record(spec);
        final Recording second = FalconRecorder.record(spec);

        final NodeRecording firstNode = first.nodes().getFirst();
        final NodeRecording secondNode = second.nodes().getFirst();
        assertThat(secondNode.startTime()).isEqualTo(firstNode.startTime());
        assertThat(secondNode.creatorSeed()).isEqualTo(firstNode.creatorSeed());
        assertThat(describe(secondNode.steps())).containsExactlyElementsOf(describe(firstNode.steps()));
    }

    @Test
    @DisplayName("No wiring hop adds a tick")
    void noWiringHopAddsATick() {
        final NodeRecording node = FalconRecorder.record(
                        RecordingSpec.builder(10, RANDOM_SEED, 5_000).build())
                .nodes()
                .getFirst();

        final Map<Hash, Instant> receivedTimes = new HashMap<>();
        final Set<Hash> waitingEvents = new HashSet<>();
        final Map<Hash, Instant> creationTimes = new HashMap<>();
        final Set<Hash> released = new HashSet<>();
        EventWindow window = EventWindow.getGenesisEventWindow();
        int checked = 0;
        for (final Step step : node.steps()) {
            switch (step) {
                case Received r -> {
                    final PlatformEvent event = r.event();
                    if (!released.contains(event.getHash())) {
                        receivedTimes.put(event.getHash(), r.time());
                        if (missesParent(event, released, window)) {
                            waitingEvents.add(event.getHash());
                        }
                    }
                }
                case CreationAttempt a -> {
                    if (a.created() != null) {
                        creationTimes.put(a.created().getHash(), a.time());
                    }
                }
                case Released r -> {
                    final Hash hash = r.event().getHash();
                    released.add(hash);
                    if (r.event().getCreatorId().equals(node.nodeId())) {
                        assertThat(r.time()).as("Release of own event %s", hash).isEqualTo(creationTimes.get(hash));
                        checked++;
                    } else if (!waitingEvents.contains(hash)) {
                        assertThat(r.time()).as("Release of event %s", hash).isEqualTo(receivedTimes.get(hash));
                        checked++;
                    }
                }
                case NewEventWindow w -> window = w.window();
                case PlatformStatusChange _ -> {}
            }
        }
        // A child arrives before its parent only if it takes a faster route; in this recording, about 1% of the events
        // waited. Many waiting events would leave this test with little to check.
        assertThat(checked)
                .as("Released events that did not wait for a parent")
                .isGreaterThanOrEqualTo(released.size() * 9 / 10);
    }

    @Test
    @DisplayName("With a constant shape, every event carries exactly its transactions")
    void constantShapeGivesExactTransactions() {
        final EventShape shape = new EventShape(constant(4), constant(2), constant(100));
        final RecordingSpec spec =
                RecordingSpec.builder(4, RANDOM_SEED, 1_000).shape(shape).build();
        final NodeRecording node = FalconRecorder.record(spec).nodes().getFirst();

        final List<PlatformEvent> events = events(node.steps());
        assertThat(events).isNotEmpty();
        for (final PlatformEvent event : events) {
            assertThat(event.getGossipEvent().transactions())
                    .as("Transactions of event %s", event.getHash())
                    .hasSize(2)
                    .allSatisfy(transaction -> assertThat(transaction.length()).isEqualTo(100));
        }
    }

    @ParameterizedTest
    @EnumSource(SigningSchema.class)
    @DisplayName("With a signing schema, every event's signature verifies")
    void signaturesVerify(@NonNull final SigningSchema signingSchema) {
        final RecordingSpec spec = RecordingSpec.builder(4, RANDOM_SEED, 500)
                .signingSchema(signingSchema)
                .build();
        final Recording recording = FalconRecorder.record(spec);

        final List<PlatformEvent> events = events(recording.nodes().getFirst().steps());
        assertThat(events).isNotEmpty();
        for (final PlatformEvent event : events) {
            final PublicKey publicKey = requireNonNull(recording.roster().rosterEntry(event.getCreatorId()))
                    .gossipCaCertificate()
                    .getPublicKey();
            final Bytes hash = requireNonNull(event.getHash()).getBytes();
            assertThat(CryptoUtils.verifySignature(hash, event.getSignature(), publicKey))
                    .as("Signature of event %s", event.getHash())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("A rebuilt event creator replays the recording and creates the same events")
    void rebuiltCreatorCreatesSameEvents() {
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(10, RANDOM_SEED, 5_000).build());
        final NodeRecording node = recording.nodes().getFirst();

        final FakeTime time = new FakeTime(node.startTime(), Duration.ZERO);
        final Queue<List<Bytes>> transactions = new ArrayDeque<>();
        for (final Step step : node.steps()) {
            if (step instanceof final CreationAttempt a && a.created() != null) {
                transactions.add(a.created().getGossipEvent().transactions());
            }
        }
        final EventTransactionSupplier transactionSupplier = () -> transactions.remove().stream()
                .map(transaction -> new TimestampedTransaction(transaction, time.now()))
                .toList();
        final EventCreationManager manager = eventCreationManager(
                node.configuration(), time, recording.roster(), node.nodeId(), node.creatorSeed(), transactionSupplier);

        int created = 0;
        for (final Step step : node.steps()) {
            time.tick(Duration.between(time.now(), step.time()));
            switch (step) {
                case CreationAttempt a -> {
                    final PlatformEvent event = manager.maybeCreateEvent();
                    assertThat(hashOf(event))
                            .as("Event created at %s", a.time())
                            .isEqualTo(hashOf(a.created()));
                    if (event != null) {
                        created++;
                    }
                }
                case Released r -> manager.registerEvent(r.event());
                case NewEventWindow w -> manager.setEventWindow(w.window());
                case PlatformStatusChange s -> manager.updatePlatformStatus(s.status());
                case Received _ -> {}
            }
        }
        assertThat(created).isPositive();
        assertThat(transactions).isEmpty();
    }

    /**
     * Builds an event creation manager as a benchmark rebuilds the one of a recorded node.
     */
    @NonNull
    private static EventCreationManager eventCreationManager(
            @NonNull final Configuration configuration,
            @NonNull final FakeTime time,
            @NonNull final RosterWrapper roster,
            @NonNull final NodeId nodeId,
            final long creatorSeed,
            @NonNull final EventTransactionSupplier transactionSupplier) {
        final Metrics metrics = new NoOpMetrics();
        final TipsetEventCreator eventCreator = new TipsetEventCreator(
                configuration,
                metrics,
                time,
                new SecureRandomBuilder(creatorSeed).get(),
                _ -> Bytes.EMPTY,
                roster,
                nodeId,
                transactionSupplier);
        return new DefaultEventCreationManager(configuration, metrics, time, () -> false, eventCreator, roster, nodeId);
    }

    /**
     * Checks whether a parent of an event is neither released nor ancient, so that the event waits in the orphan
     * buffer.
     */
    private static boolean missesParent(
            @NonNull final PlatformEvent event, @NonNull final Set<Hash> released, @NonNull final EventWindow window) {
        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            if (!released.contains(parent.hash()) && !window.isAncient(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns every event in the steps.
     */
    @NonNull
    private static List<PlatformEvent> events(@NonNull final List<Step> steps) {
        final List<PlatformEvent> events = new ArrayList<>();
        for (final Step step : steps) {
            switch (step) {
                case Received r -> events.add(r.event());
                case Released r -> events.add(r.event());
                case CreationAttempt a -> {
                    if (a.created() != null) {
                        events.add(a.created());
                    }
                }
                case NewEventWindow _, PlatformStatusChange _ -> {}
            }
        }
        return events;
    }

    /**
     * Describes each step by its kind, its time, and what it carries, with an event by its hash.
     */
    @NonNull
    private static List<String> describe(@NonNull final List<Step> steps) {
        return steps.stream()
                .map(step -> switch (step) {
                    case Received r -> "Received " + r.time() + " " + r.sender() + " " + hashOf(r.event());
                    case Released r -> "Released " + r.time() + " " + hashOf(r.event());
                    case CreationAttempt a -> "CreationAttempt " + a.time() + " " + hashOf(a.created());
                    case NewEventWindow w -> "NewEventWindow " + w.time() + " " + w.window();
                    case PlatformStatusChange s -> "PlatformStatusChange " + s.time() + " " + s.status();
                })
                .toList();
    }

    @Nullable
    private static Hash hashOf(@Nullable final PlatformEvent event) {
        return event == null ? null : event.getHash();
    }
}
