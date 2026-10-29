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
import org.hiero.base.crypto.SignatureType;
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
 *   <li>is hashed by its creator, and signed with its creator's key if keys are given; otherwise its signature is
 *       random bytes of the length of an RSA signature;</li>
 *   <li>is returned as its creator created it, with the origin {@link EventOrigin#RUNTIME}.</li>
 * </ul>
 *
 * <p>A benchmark gets the events as another node receives them from {@link #receive(List)} or
 * {@link #receiveUnhashed(List)}. The streams of the gossip protocol write and read each event
 * ({@link SerializableDataOutputStream#writePbjRecord}, {@link SerializableDataInputStream#readPbjRecord}), so that its
 * lists and byte buffers are the ones the gossip layer's parser creates. The event is then built from the parsed gossip
 * event with the origin {@link EventOrigin#GOSSIP}, as the gossip layer builds it. {@link #receive(List)} also hashes
 * it, as the intake does; {@link #receiveUnhashed(List)} leaves the hashing to a benchmark of the hasher.
 *
 * <p>A benchmark whose code under test leaves state in an event receives fresh copies in every pass, with new lists and
 * byte buffers. For example, a PBJ {@code Bytes} caches the memory segment that the Ed25519 verifier creates from it
 * ({@code Bytes#toMemorySegment}); a node creates it once for every event it receives, but a benchmark that verifies the
 * same objects in every pass would create it in the first pass only.
 *
 * <p>A benchmark generates fixed shapes, one parameter set each, for example at chosen quantiles of a production table
 * ({@link EmpiricalDistribution#quantile(double)}). With a mix, the upper percentiles would measure the heaviest events
 * instead of how the time of one kind of call varies.
 *
 * <p>The same arguments give the same events, hashes, and signatures; only the time received of a received event,
 * which the gossip layer sets when it builds the event, is the time it was received. No production code runs except
 * the hasher, the signer, and the gossip streams, so the events stay the same when the event creator or the consensus
 * code changes. The received events pass internal validation ({@code DefaultEventFieldValidator}) as long as their
 * transactions stay within the transaction limits.
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
     * Generates unsigned events: every signature is made up of random bytes of the length of an RSA signature, and does
     * not verify.
     *
     * @param shape  the shape of the events
     * @param roster the creators of the events
     * @param count  the number of events
     * @param seed   the seed of all random choices
     * @return the events as their creators created them, in the order they were generated
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
     * @return the events as their creators created them, in the order they were generated
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

    /**
     * Receives events as another node receives them through gossip, and hashes them as the intake does (see the class
     * description). Each call creates new lists and byte buffers, which no code has used yet.
     *
     * @param events the events, for example generated ones
     * @return the received events, equal to the given ones and hashed, in the same order
     */
    @NonNull
    public static List<PlatformEvent> receive(@NonNull final List<PlatformEvent> events) {
        final PbjStreamHasher hasher = new PbjStreamHasher();
        final List<PlatformEvent> received = new ArrayList<>(events.size());
        for (final PlatformEvent event : events) {
            final PlatformEvent receivedEvent = receiveUnhashed(event);
            hasher.hashEvent(receivedEvent);
            received.add(receivedEvent);
        }
        return Collections.unmodifiableList(received);
    }

    /**
     * Receives events as another node receives them through gossip, as {@link #receive(List)} does, but does not hash
     * them: the input of a benchmark of the hasher, which the intake hashes first.
     *
     * @param events the events, for example generated ones
     * @return the received events, equal to the given ones and not hashed, in the same order
     */
    @NonNull
    public static List<PlatformEvent> receiveUnhashed(@NonNull final List<PlatformEvent> events) {
        final List<PlatformEvent> received = new ArrayList<>(events.size());
        for (final PlatformEvent event : events) {
            received.add(receiveUnhashed(event));
        }
        return Collections.unmodifiableList(received);
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
                    ? madeUpSignature(random)
                    : signers.get(creator).sign(unsignedEvent.getHash().getBytes());
            events.add(new PlatformEvent(unsignedEvent, signature, EventOrigin.RUNTIME));
        }
        return Collections.unmodifiableList(events);
    }

    /**
     * Receives an event as another node receives it through gossip: sends it through the streams that the gossip
     * protocol writes and reads events with, and builds the event from the gossip event that the receiving side parses.
     */
    @NonNull
    private static PlatformEvent receiveUnhashed(@NonNull final PlatformEvent event) {
        try {
            final ByteArrayOutputStream sent = new ByteArrayOutputStream();
            try (final SerializableDataOutputStream out = new SerializableDataOutputStream(sent)) {
                out.writePbjRecord(event.getGossipEvent(), GossipEvent.PROTOBUF);
            }
            try (final SerializableDataInputStream in =
                    new SerializableDataInputStream(new ByteArrayInputStream(sent.toByteArray()))) {
                final GossipEvent gossipEvent = in.readPbjRecord(GossipEvent.PROTOBUF);
                return new PlatformEvent(gossipEvent, EventOrigin.GOSSIP);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to send an event through the gossip streams", e);
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

    /** Makes up a signature: random bytes of the length of an RSA signature, which do not verify. */
    @NonNull
    private static Bytes madeUpSignature(@NonNull final Random random) {
        final byte[] signature = new byte[SignatureType.RSA.signatureLength()];
        random.nextBytes(signature);
        return Bytes.wrap(signature);
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
