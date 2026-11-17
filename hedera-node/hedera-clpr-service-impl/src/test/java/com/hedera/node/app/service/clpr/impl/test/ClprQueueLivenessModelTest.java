// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_QUEUE_FULL;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator.ReplyValidation.AckedThrough;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator.ValidatedBundle;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exhaustive small-state model that uses the production acknowledgement/prefix validator.
 *
 * <p>Two ACTIVE ledgers start at Q=2 and may independently expand once to Q=3, advertising that
 * change lazily. A same-depth configuration update may also make either advertisement stale.
 * Exploration includes arbitrary interleavings of sends, fresh partial bundles, ACKs,
 * and every feasible number of nested sends per DATA/reply callback. A single connector has 100% quota. Message sizes,
 * proofs, execution and transport succeed; other throttle values remain fixed. Message ids are
 * translated after acknowledgement, so exploration covers unbounded operation, not a fixed trace.
 *
 * <p>The liveness claim is deliberately conditional: after new sends/config changes/callback sends
 * stop, every reachable state has a path to drain using only bundles. Check both maximal bundles
 * and arbitrary nonempty prefixes; this does not claim progress without endpoint scheduling.
 */
class ClprQueueLivenessModelTest {
    private static final ClprBundleValidator VALIDATOR = new ClprBundleValidator();

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void expandOnlyGovernancePreservesBoundsAndADrainPath(final int bundleLimit) {
        // Both idle peers have a newer local configuration with unchanged queue credit.
        final var initialSide = new Side(2, 2, true, 1, 0, 0, List.of());
        final var initial = new State(initialSide, initialSide);
        final Set<State> reachable = new HashSet<>();
        final var pending = new ArrayDeque<State>();
        reachable.add(initial);
        pending.add(initial);
        while (!pending.isEmpty()) {
            final var state = pending.removeFirst();
            final var successors = new ArrayList<>(bundleMoves(state, bundleLimit, false, true));
            for (int side = 0; side < 2; side++) {
                final var current = state.side(side);
                final var sent = send(current);
                if (sent != null) successors.add(state.replace(side, sent).canonical());
                if (!current.configPending)
                    successors.add(state.replace(side, current.withPendingConfig(current.limit)));
                if (current.limit == 2) successors.add(state.replace(side, current.withPendingConfig(3)));
            }
            for (final var successor : successors) {
                for (final var side : List.of(successor.a, successor.b)) {
                    assertTrue(side.next - side.acked - 1 <= 2L * side.limit, () -> "Queue overflow: " + successor);
                }
                if (reachable.add(successor)) pending.addLast(successor);
            }
            // Repeated same-depth updates expand this model to just over 50,000 distinct states.
            assertTrue(reachable.size() < 60_000, "Canonicalization must keep the model finite");
        }
        assertTrue(reachable.size() > 100, "Explore interleavings, not only the empty initial state");
        assertCanDrain(reachable, bundleLimit, true);
        assertCanDrain(reachable, bundleLimit, false);
    }

    private static void assertCanDrain(final Set<State> reachable, final int bundleLimit, final boolean maximal) {
        final Map<State, Set<State>> predecessors = new HashMap<>();
        final Set<State> drains = new HashSet<>();
        final var pending = new ArrayDeque<State>();
        for (final var state : reachable) {
            if (state.a.queue.isEmpty() && state.b.queue.isEmpty()) {
                drains.add(state);
                pending.add(state);
            }
            for (final var successor : bundleMoves(state, bundleLimit, maximal, false)) {
                assertTrue(reachable.contains(successor));
                predecessors
                        .computeIfAbsent(successor, ignored -> new HashSet<>())
                        .add(state);
            }
        }
        while (!pending.isEmpty()) {
            for (final var predecessor : predecessors.getOrDefault(pending.removeFirst(), Set.of())) {
                if (drains.add(predecessor)) pending.addLast(predecessor);
            }
        }
        final var stuck = new HashSet<>(reachable);
        stuck.removeAll(drains);
        assertTrue(
                stuck.isEmpty(),
                () -> "No drain path (maximal=" + maximal + "): "
                        + stuck.stream().findFirst());
    }

    private static List<State> bundleMoves(
            final State state, final int bundleLimit, final boolean maximal, final boolean nested) {
        final var successors = new ArrayList<State>();
        for (int sourceIndex = 0; sourceIndex < 2; sourceIndex++) {
            final var source = state.side(sourceIndex);
            final var receiver = state.side(1 - sourceIndex);
            final var available = source.queue.stream()
                    .filter(slot -> slot.id > receiver.received)
                    .limit(bundleLimit)
                    .toList();
            final int firstCount = maximal || available.isEmpty() ? available.size() : 1;
            for (int count = firstCount; count <= available.size(); count++) {
                final var messages = available.subList(0, count);
                final int callbacks = (int) messages.stream()
                        .filter(slot -> slot.kind != Kind.CONFIG)
                        .count();
                final int choices = nested ? (int) Math.pow(receiver.limit, callbacks) : 1;
                for (int mask = 0; mask < choices; mask++) {
                    final var result = receive(receiver, source.received, messages, mask);
                    if (result != null)
                        successors.add(state.replace(1 - sourceIndex, result).canonical());
                }
            }
        }
        return successors;
    }

    private static Side receive(
            final Side receiver, final long peerReceived, final List<Slot> messages, final int mask) {
        if (messages.isEmpty() && peerReceived <= receiver.acked) return null;
        final var payloads = messages.stream().map(Slot::payload).toList();
        final var channel = ClprChannel.newBuilder()
                .nextMessageId(receiver.next)
                .ackedMessageId(receiver.acked)
                .build();
        final var content = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder().receivedMessageId(peerReceived))
                .messages(payloads)
                .build();
        final AckedThrough accepted;
        try {
            final var validation = VALIDATOR.validateReplies(
                    channel,
                    new ValidatedBundle(content, payloads, Bytes.EMPTY, false, false),
                    store(receiver),
                    receiver.limit);
            assertTrue(validation instanceof AckedThrough, () -> "Honest reply order failed: " + validation);
            accepted = (AckedThrough) validation;
        } catch (final PreCheckException e) {
            assertTrue(e.responseCode() == CLPR_NO_PROGRESS || e.responseCode() == CLPR_QUEUE_FULL);
            return null;
        }
        final long ack = accepted.lastAckedMessageId();
        var current = receiver.withQueue(receiver.queue.stream()
                .filter(slot -> slot.id > ack || slot.kind == Kind.DATA)
                .toList());
        if (current.configPending && current.next - ack - 1 < current.limit) {
            current = append(current, ack, Kind.CONFIG, current.limit);
            current = new Side(
                    current.limit,
                    current.peerLimit,
                    false,
                    current.next,
                    current.acked,
                    current.received,
                    current.queue);
        }
        int remainingChoices = mask;
        for (final var message : messages.subList(0, accepted.acceptedMessageCount())) {
            switch (message.kind) {
                case CONFIG ->
                    current = new Side(
                            current.limit,
                            (int) message.value,
                            current.configPending,
                            current.next,
                            current.acked,
                            current.received,
                            current.queue);
                case DATA -> {
                    current = nestedSends(current, remainingChoices % receiver.limit);
                    remainingChoices /= receiver.limit;
                    current = append(current, ack, Kind.REPLY, message.id);
                }
                case REPLY -> {
                    assertTrue(current.queue.stream()
                            .anyMatch(slot -> slot.id == message.value && slot.kind == Kind.DATA));
                    current = current.withQueue(current.queue.stream()
                            .filter(slot -> slot.id != message.value)
                            .toList());
                    current = nestedSends(current, remainingChoices % receiver.limit);
                    remainingChoices /= receiver.limit;
                }
            }
        }
        return new Side(
                current.limit,
                current.peerLimit,
                current.configPending,
                current.next,
                ack,
                current.received + accepted.acceptedMessageCount(),
                current.queue);
    }

    private static Side nestedSends(final Side side, final int attempts) {
        var current = side;
        for (int i = 0; i < attempts; i++) {
            final var sent = send(current);
            if (sent == null) break;
            current = sent;
        }
        return current;
    }

    private static Side send(final Side side) {
        final long depthWithSend = side.next - side.acked;
        if (depthWithSend > side.limit - 1L) return null;
        if (side.queue.stream().filter(slot -> slot.kind == Kind.DATA).count() >= side.peerLimit) return null;
        var current = side;
        if (side.configPending) {
            current = append(current, side.acked, Kind.CONFIG, side.limit);
            current = new Side(
                    current.limit,
                    current.peerLimit,
                    false,
                    current.next,
                    current.acked,
                    current.received,
                    current.queue);
        }
        return append(current, side.acked, Kind.DATA, 0);
    }

    private static Side append(final Side side, final long ack, final Kind kind, final long value) {
        assertTrue(side.next - ack - 1 < 2L * side.limit, () -> "Queue fills during dispatch: " + side);
        final var queue = new ArrayList<>(side.queue);
        queue.add(new Slot(side.next, kind, value));
        return new Side(
                side.limit,
                side.peerLimit,
                side.configPending,
                side.next + 1,
                side.acked,
                side.received,
                List.copyOf(queue));
    }

    private static ReadableMessageQueueStore store(final Side side) {
        return new ReadableMessageQueueStore() {
            @Override
            public ClprMessageValue getMessage(final Bytes channelId, final long messageId) {
                return side.queue.stream()
                        .filter(slot -> slot.id == messageId)
                        .findFirst()
                        .map(slot -> ClprMessageValue.newBuilder()
                                .payload(slot.payload())
                                .build())
                        .orElse(null);
            }

            @Override
            public long sizeOfState() {
                return side.queue.size();
            }
        };
    }

    private enum Kind {
        DATA,
        REPLY,
        CONFIG
    }

    private record Slot(long id, Kind kind, long value) {
        private ClprMessagePayload payload() {
            return switch (kind) {
                case DATA ->
                    ClprMessagePayload.newBuilder().message(ClprMessage.DEFAULT).build();
                case REPLY ->
                    ClprMessagePayload.newBuilder()
                            .messageReply(ClprMessageReply.newBuilder().messageId(value))
                            .build();
                case CONFIG ->
                    ClprMessagePayload.newBuilder()
                            .control(ClprControlMessage.DEFAULT)
                            .build();
            };
        }
    }

    private record Side(
            int limit, int peerLimit, boolean configPending, long next, long acked, long received, List<Slot> queue) {
        private Side withPendingConfig(final int newLimit) {
            return new Side(newLimit, peerLimit, true, next, acked, received, queue);
        }

        private Side withQueue(final List<Slot> updated) {
            return new Side(limit, peerLimit, configPending, next, acked, received, updated);
        }

        private Side shift(final Side peer) {
            final var shifted = queue.stream()
                    .map(slot -> new Slot(
                            slot.id - acked,
                            slot.kind,
                            slot.kind != Kind.REPLY
                                    ? slot.value
                                    : slot.id <= peer.received ? 0 : slot.value - peer.acked))
                    .toList();
            return new Side(limit, peerLimit, configPending, next - acked, 0, received - peer.acked, shifted);
        }
    }

    private record State(Side a, Side b) {
        private Side side(final int index) {
            return index == 0 ? a : b;
        }

        private State replace(final int index, final Side updated) {
            return index == 0 ? new State(updated, b) : new State(a, updated);
        }

        private State canonical() {
            return new State(a.shift(b), b.shift(a));
        }
    }
}
