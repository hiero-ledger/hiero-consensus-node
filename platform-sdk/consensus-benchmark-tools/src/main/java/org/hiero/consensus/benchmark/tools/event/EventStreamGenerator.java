// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
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
 *   <li>has a creator drawn uniformly from a roster of {@code numNodes} nodes;</li>
 *   <li>has a self parent and {@link EventShape#otherParents()} other parents, all made up: random hashes, distinct
 *       creators, and birth rounds at or below the event's own;</li>
 *   <li>carries {@link EventShape#transactionsPerEvent()} transactions of random bytes, each of a size drawn from
 *       {@link EventShape#transactionSize()};</li>
 *   <li>is hashed, and signed with its creator's key if a signing schema is given; otherwise its signature is
 *       empty;</li>
 *   <li>has the origin {@link EventOrigin#GOSSIP}, as an event received from another node.</li>
 * </ul>
 *
 * <p>The same arguments give the same events, hashes, and signatures. No production code runs except the hasher and
 * the signer, so the events stay the same when the event creator or the consensus code changes. The events pass
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
     * Generates the events.
     *
     * @param shape         the shape of the events
     * @param numNodes      the number of nodes in the roster
     * @param signingSchema the schema of the nodes' signing keys, or {@code null} for unsigned events
     * @param count         the number of events
     * @param seed          the seed of all random choices
     * @return the roster, the keys, and the events
     * @throws IllegalArgumentException if {@code numNodes} or {@code count} is out of range, or the shape draws a
     *                                  negative count, more other parents than there are other nodes, or an empty
     *                                  transaction
     */
    @NonNull
    public static EventStream generate(
            @NonNull final EventShape shape,
            final int numNodes,
            @Nullable final SigningSchema signingSchema,
            final int count,
            final long seed) {
        if (numNodes < 1 || count < 0) {
            throw new IllegalArgumentException(
                    "Expected at least one node and a non-negative count, but got " + numNodes + " and " + count);
        }
        final Random random = new Random(seed);
        final List<NodeId> nodeIds =
                IntStream.range(0, numNodes).mapToObj(NodeId::of).toList();
        final Map<NodeId, KeysAndCerts> keys = signingSchema == null ? Map.of() : generateKeys(nodeIds, signingSchema);
        final Map<NodeId, PlatformSigner> signers = new HashMap<>();
        keys.forEach((nodeId, nodeKeys) -> signers.put(nodeId, new PlatformSigner(nodeKeys)));
        final PbjStreamHasher hasher = new PbjStreamHasher();

        final List<PlatformEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final NodeId creator = nodeIds.get(random.nextInt(numNodes));
            final Instant timeCreated = START_TIME.plusMillis(i);
            final UnsignedEvent unsignedEvent = new UnsignedEvent(
                    creator,
                    parents(creator, nodeIds, shape.otherParents().sample(random), random),
                    BIRTH_ROUND,
                    timeCreated,
                    transactions(shape, random),
                    // the creator's range; nextInt, not nextLong(origin, bound), whose algorithm a JDK may change
                    random.nextInt(numNodes + 1),
                    timeCreated,
                    List.of());
            hasher.hashUnsignedEvent(unsignedEvent);
            final Bytes signature = signingSchema == null
                    ? Bytes.EMPTY
                    : signers.get(creator).sign(unsignedEvent.getHash().getBytes());
            events.add(new PlatformEvent(unsignedEvent, signature, EventOrigin.GOSSIP));
        }
        return new EventStream(roster(nodeIds, keys), keys, Collections.unmodifiableList(events));
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

    /** Draws the transactions of an event: random bytes of the drawn sizes. */
    @NonNull
    private static List<Bytes> transactions(@NonNull final EventShape shape, @NonNull final Random random) {
        final long count = shape.transactionsPerEvent().sample(random);
        if (count < 0) {
            throw new IllegalArgumentException("The shape drew " + count + " transactions");
        }
        final List<Bytes> transactions = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            final long size = shape.transactionSize().sample(random);
            if (size < 1) {
                throw new IllegalArgumentException("The shape drew a transaction of " + size + " bytes");
            }
            final byte[] transaction = new byte[Math.toIntExact(size)];
            random.nextBytes(transaction);
            transactions.add(Bytes.wrap(transaction));
        }
        return transactions;
    }

    /** Generates the nodes' keys with {@link KeysAndCertsGenerator}, which derives them from the node IDs. */
    @NonNull
    private static Map<NodeId, KeysAndCerts> generateKeys(
            @NonNull final List<NodeId> nodeIds, @NonNull final SigningSchema signingSchema) {
        try {
            return Map.copyOf(KeysAndCertsGenerator.generateKeysAndCerts(nodeIds, signingSchema));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating keys", e);
        } catch (final ExecutionException | KeyStoreException e) {
            throw new IllegalStateException("Failed to generate keys", e);
        }
    }

    /** Builds a roster of equal weights, with each node's signing certificate if there are keys. */
    @NonNull
    private static RosterWrapper roster(
            @NonNull final List<NodeId> nodeIds, @NonNull final Map<NodeId, KeysAndCerts> keys) {
        final List<RosterEntry> entries = new ArrayList<>();
        for (final NodeId nodeId : nodeIds) {
            entries.add(RosterEntry.newBuilder()
                    .nodeId(nodeId.id())
                    .weight(1)
                    .gossipCaCertificate(certificate(keys.get(nodeId)))
                    .build());
        }
        return RosterWrapper.of(new Roster(entries));
    }

    @NonNull
    private static Bytes certificate(@Nullable final KeysAndCerts nodeKeys) {
        if (nodeKeys == null) {
            return Bytes.EMPTY;
        }
        try {
            return Bytes.wrap(nodeKeys.sigCert().getEncoded());
        } catch (final CertificateEncodingException e) {
            throw new IllegalStateException("Failed to encode a signing certificate", e);
        }
    }
}
