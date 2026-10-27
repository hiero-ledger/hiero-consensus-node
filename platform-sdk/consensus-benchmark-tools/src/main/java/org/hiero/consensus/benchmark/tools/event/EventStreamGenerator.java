// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * Generates independent events for the benchmarks that process one event at a time and need no graph, such as hashing,
 * signing, serialization, and validation.
 *
 * <p>Each event:
 * <ul>
 *   <li>has a creator drawn uniformly from the roster;</li>
 *   <li>has a self parent and {@link EventShape#otherParents()} other parents, all made up: random hashes, distinct
 *       creators from the roster, and birth rounds at or below the event's own;</li>
 *   <li>carries {@link EventShape#transactionsPerEvent()} transactions of random bytes, each of a size drawn from
 *       {@link EventShape#transactionSize()};</li>
 *   <li>is signed with its creator's key if keys are given; otherwise its signature is empty;</li>
 *   <li>arrives as an event received from another node. The streams of the gossip protocol write and read it
 *       ({@link SerializableDataOutputStream#writePbjRecord}, {@link SerializableDataInputStream#readPbjRecord}), so
 *       that its lists and byte buffers are the ones the gossip layer's parser creates. It is then built from the
 *       parsed gossip event with the origin {@link EventOrigin#GOSSIP}, as the gossip layer builds it, and hashed, as
 *       the intake hashes it.</li>
 * </ul>
 *
 * <p>A benchmark generates fixed shapes, one parameter set each, for example at chosen quantiles of a production table
 * ({@link EmpiricalDistribution#quantile(double)}). With a mix, the upper percentiles would measure the heaviest events
 * instead of how the time of one kind of call varies.
 *
 * <p>The same arguments give the same events, hashes, and signatures; only the time received, which the gossip layer
 * sets when it builds the event, is the time of generation. No production code runs except the hasher, the signer, and
 * the gossip streams, so the events stay the same when the event creator or the consensus code changes. The events pass
 * internal validation ({@code DefaultEventFieldValidator}) as long as their transactions stay within the transaction
 * limits.
 *
 * <p>The hash type, SHA-384 or SHA-256, follows the static cutover setting of {@link EventHashFactory}, which is
 * SHA-384 unless something in the JVM changes it. The same arguments give the same events only under the same setting.
 * A benchmark sets it explicitly in its trial setup, before it generates the events: for example,
 * {@code EventHashFactory.initialize(0)} hashes every event with SHA-256.
 *
 * <p>The birth rounds and creation times are fixed values that make the events valid; they are not taken from
 * production data.
 */
public final class EventStreamGenerator {

    /**
     * The birth round of every event. Each parent's birth round is this one or the one before. A benchmark that applies
     * an event window builds it from this value: with an ancient threshold above it, every event is ancient.
     */
    public static final long BIRTH_ROUND = 100;

    /** The creation time of the first event. Each following event is created one millisecond later. */
    private static final Instant START_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private EventStreamGenerator() {}

    /**
     * Generates unsigned events: every signature is empty.
     *
     * @param shape  the shape of the events
     * @param roster the creators of the events
     * @param count  the number of events
     * @param seed   the seed of all random choices
     * @return the events, in the order they were generated
     * @throws IllegalArgumentException if the roster is empty, {@code count} is negative, or the shape draws a negative
     *                                  count, more other parents than there are other nodes, or an empty transaction
     */
    @NonNull
    public static List<PlatformEvent> generateUnsignedEvents(
            @NonNull final EventShape shape, @NonNull final RosterWrapper roster, final int count, final long seed) {
        return generateEvents(shape, roster, Map.of(), count, seed);
    }

    /**
     * Generates events signed with their creators' keys.
     *
     * @param shape  the shape of the events
     * @param roster the creators of the events
     * @param count  the number of events
     * @param seed   the seed of all random choices
     * @param keys   the keys of every node in the roster
     * @return the events, in the order they were generated
     * @throws IllegalArgumentException if a node in the roster has no keys, or for the reasons of
     *                                  {@link #generateUnsignedEvents(EventShape, RosterWrapper, int, long)}
     */
    @NonNull
    public static List<PlatformEvent> generateSignedEvents(
            @NonNull final EventShape shape,
            @NonNull final RosterWrapper roster,
            final int count,
            final long seed,
            @NonNull final Map<NodeId, KeysAndCerts> keys) {
        final Map<NodeId, PlatformSigner> signers = new HashMap<>();
        for (final NodeId nodeId : roster.nodeIds()) {
            final KeysAndCerts nodeKeys = keys.get(nodeId);
            if (nodeKeys == null) {
                throw new IllegalArgumentException("No keys for node " + nodeId);
            }
            signers.put(nodeId, new PlatformSigner(nodeKeys));
        }
        return generateEvents(shape, roster, signers, count, seed);
    }

    /** Generates the events, signed if there are signers. */
    @NonNull
    private static List<PlatformEvent> generateEvents(
            @NonNull final EventShape shape,
            @NonNull final RosterWrapper roster,
            @NonNull final Map<NodeId, PlatformSigner> signers,
            final int count,
            final long seed) {
        if (roster.size() < 1 || count < 0) {
            throw new IllegalArgumentException(
                    "Expected at least one node and a non-negative count, but got " + roster.size() + " and " + count);
        }
        final Random random = new Random(seed);
        final List<NodeId> nodeIds = roster.nodeIds();
        final PbjStreamHasher hasher = new PbjStreamHasher();

        final List<PlatformEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final NodeId creator = nodeIds.get(random.nextInt(nodeIds.size()));
            final Instant timeCreated = START_TIME.plusMillis(i);
            final UnsignedEvent unsignedEvent = new UnsignedEvent(
                    creator,
                    parents(creator, nodeIds, shape.otherParents().sample(random), random),
                    BIRTH_ROUND,
                    timeCreated,
                    shape.sampleTransactions(random),
                    // the creator's range; nextInt, not nextLong(origin, bound), whose algorithm a JDK may change
                    random.nextInt(nodeIds.size() + 1),
                    timeCreated,
                    List.of());
            // The creator hashes and signs the event.
            hasher.hashUnsignedEvent(unsignedEvent);
            final Bytes signature = signers.isEmpty()
                    ? Bytes.EMPTY
                    : signers.get(creator).sign(unsignedEvent.getHash().getBytes());
            final PlatformEvent created = new PlatformEvent(unsignedEvent, signature, EventOrigin.RUNTIME);

            // Another node receives it through gossip and hashes it in its intake.
            final PlatformEvent received = new PlatformEvent(receive(created.getGossipEvent()), EventOrigin.GOSSIP);
            hasher.hashEvent(received);
            events.add(received);
        }
        return Collections.unmodifiableList(events);
    }

    /**
     * Sends an event through the streams that the gossip protocol writes and reads events with, and returns the copy
     * that the receiving side parses.
     */
    @NonNull
    private static GossipEvent receive(@NonNull final GossipEvent event) {
        try {
            final ByteArrayOutputStream sent = new ByteArrayOutputStream();
            try (final SerializableDataOutputStream out = new SerializableDataOutputStream(sent)) {
                out.writePbjRecord(event, GossipEvent.PROTOBUF);
            }
            try (final SerializableDataInputStream in =
                    new SerializableDataInputStream(new ByteArrayInputStream(sent.toByteArray()))) {
                return in.readPbjRecord(GossipEvent.PROTOBUF);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to send a generated event through the gossip streams", e);
        }
    }

    /**
     * Makes up the parents of an event: a self parent first, as the event creator orders them, then the other parents,
     * each from a different node.
     */
    @NonNull
    private static List<EventDescriptorWrapper> parents(
            @NonNull final NodeId creator,
            @NonNull final List<NodeId> nodeIds,
            final long otherParents,
            @NonNull final Random random) {
        final List<NodeId> otherNodes = new ArrayList<>(nodeIds);
        otherNodes.remove(creator);
        if (otherParents < 0 || otherParents > otherNodes.size()) {
            throw new IllegalArgumentException("The shape drew " + otherParents + " other parents, but there are "
                    + otherNodes.size() + " other nodes");
        }
        Collections.shuffle(otherNodes, random);

        final List<EventDescriptorWrapper> parents = new ArrayList<>();
        parents.add(madeUpParent(creator, random));
        for (final NodeId otherCreator : otherNodes.subList(0, (int) otherParents)) {
            parents.add(madeUpParent(otherCreator, random));
        }
        return parents;
    }

    /** Makes up a parent: a random hash of the right length for its birth round. */
    @NonNull
    private static EventDescriptorWrapper madeUpParent(@NonNull final NodeId creator, @NonNull final Random random) {
        final long birthRound = BIRTH_ROUND - random.nextInt(2);
        final DigestType digestType = EventHashFactory.getTypeForBirthRound(birthRound);
        final byte[] hash = new byte[digestType.digestLength()];
        random.nextBytes(hash);
        return new EventDescriptorWrapper(new Hash(hash, digestType), creator, birthRound);
    }
}
