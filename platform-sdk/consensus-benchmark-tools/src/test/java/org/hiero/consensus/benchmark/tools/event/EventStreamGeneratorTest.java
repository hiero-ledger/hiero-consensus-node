// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.event.validation.DefaultEventFieldValidator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWithKeys;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.consensus.transaction.TransactionLimits;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks that {@link EventStreamGenerator} gives the same events for the same seed, received events that equal the
 * created ones and pass internal validation, and signatures that verify against the roster.
 */
class EventStreamGeneratorTest {

    private static final int NUM_NODES = 4;
    private static final int COUNT = 1_000;
    private static final long SEED = 7;

    /** A roster whose node IDs are 1 to 3 apart, with certificates that no test signs with. */
    private static final RosterWrapper ROSTER = RosterWrapperFactory.randomRoster(new Random(SEED), NUM_NODES);

    /**
     * The consensus layer's default limits ({@code ExecutionLayer#DEFAULT_TRANSACTION_LIMITS}). Hedera allows more bytes
     * per event ({@code HederaConfig}), so an event within these limits is within Hedera's too.
     */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(133_120, 245_760);

    /** A mix of shapes, with at most 20 × 6,144 = 122,880 transaction bytes per event. */
    private static final EventShape MIXED = new EventShape(
            EmpiricalDistribution.of(new long[] {0, 1, 2, 3}, new double[] {1, 6, 2, 1}),
            EmpiricalDistribution.of(new long[] {0, 1, 5, 20}, new double[] {5, 3, 1, 1}),
            EmpiricalDistribution.of(new long[] {1, 100, 445, 6_144}, new double[] {1, 4, 4, 1}));

    @Test
    void eventsFollowTheShape() {
        final EventShape shape = new EventShape(constant(2), constant(3), constant(445));
        final List<PlatformEvent> events = EventStreamGenerator.generateUnsignedEvents(shape, ROSTER, COUNT, SEED);

        assertThat(events).hasSize(COUNT);
        for (final PlatformEvent event : events) {
            assertThat(event.getSelfParent()).isEqualTo(event.getAllParents().getFirst());
            assertThat(event.getSelfParent().creator()).isEqualTo(event.getCreatorId());
            assertThat(event.getOtherParents()).hasSize(2);
            assertThat(event.getAllParents().stream().map(EventDescriptorWrapper::creator))
                    .doesNotHaveDuplicates()
                    .isSubsetOf(ROSTER.nodeIds());
            assertThat(event.getBirthRound()).isEqualTo(EventStreamGenerator.BIRTH_ROUND);
            assertThat(event.getAllParents())
                    .allSatisfy(parent -> assertThat(parent.birthRound()).isLessThanOrEqualTo(event.getBirthRound()));
            assertThat(event.getTransactions()).hasSize(3).allSatisfy(transaction -> assertThat(transaction.getSize())
                    .isEqualTo(445));
            assertThat(event.getHash()).isNotNull();
            assertThat(event.getOrigin()).isEqualTo(EventOrigin.RUNTIME);
        }
        assertThat(events.stream().map(PlatformEvent::getCreatorId).distinct())
                .containsExactlyInAnyOrderElementsOf(ROSTER.nodeIds());
    }

    @Test
    void receivedEventsEqualTheCreatedOnes() {
        final List<PlatformEvent> events = EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED);

        final List<PlatformEvent> received = EventStreamGenerator.receive(events);

        assertThat(gossipEvents(received)).isEqualTo(gossipEvents(events));
        assertThat(hashes(received)).isEqualTo(hashes(events));
        assertThat(received).allSatisfy(event -> assertThat(event.getOrigin()).isEqualTo(EventOrigin.GOSSIP));
    }

    @Test
    void unhashedReceivedEventsHaveNoHash() {
        final List<PlatformEvent> events = EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED);

        final List<PlatformEvent> received = EventStreamGenerator.receiveUnhashed(events);

        assertThat(gossipEvents(received)).isEqualTo(gossipEvents(events));
        assertThat(received).allSatisfy(event -> {
            assertThat(event.getHash()).isNull();
            assertThat(event.getOrigin()).isEqualTo(EventOrigin.GOSSIP);
        });
    }

    @Test
    void sameSeedGivesSameUnsignedEvents() {
        assertSameEvents(
                EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED),
                EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED),
                EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED + 1));
    }

    @ParameterizedTest
    @EnumSource(SigningSchema.class)
    void sameSeedGivesSameSignedEvents(final SigningSchema signingSchema) {
        final RosterWithKeys network = network(signingSchema);
        final RosterWrapper roster = network.roster();
        final Map<NodeId, KeysAndCerts> keys = network.privateKeys();

        assertSameEvents(
                EventStreamGenerator.generateSignedEvents(MIXED, roster, COUNT, SEED, keys),
                EventStreamGenerator.generateSignedEvents(MIXED, roster, COUNT, SEED, keys),
                EventStreamGenerator.generateSignedEvents(MIXED, roster, COUNT, SEED + 1, keys));
    }

    static Stream<Named<EventShape>> shapes() {
        return Stream.of(
                Named.of("mixed", MIXED),
                Named.of("one transaction", new EventShape(constant(1), constant(1), constant(445))),
                Named.of("no transaction", new EventShape(constant(1), constant(0), constant(445))));
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void everyReceivedEventPassesFieldValidation(final EventShape shape) {
        final DefaultEventFieldValidator validator =
                new DefaultEventFieldValidator(new NoOpMetrics(), Time.getCurrent(), TRANSACTION_LIMITS);

        final List<PlatformEvent> events = EventStreamGenerator.generateUnsignedEvents(shape, ROSTER, COUNT, SEED);

        assertThat(EventStreamGenerator.receive(events)).allMatch(validator::isValid);
    }

    @Test
    void cutoverToSha256() {
        final DefaultEventFieldValidator validator =
                new DefaultEventFieldValidator(new NoOpMetrics(), Time.getCurrent(), TRANSACTION_LIMITS);
        EventHashFactory.initialize(0);
        try {
            final List<PlatformEvent> events = EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED);

            assertThat(events).allSatisfy(event -> {
                assertThat(event.getHash().getDigestType()).isEqualTo(DigestType.SHA_256);
                assertThat(event.getAllParents())
                        .allSatisfy(parent ->
                                assertThat(parent.hash().getDigestType()).isEqualTo(DigestType.SHA_256));
            });
            assertThat(EventStreamGenerator.receive(events)).allMatch(validator::isValid);
        } finally {
            // the default, so that the other tests hash with SHA-384
            EventHashFactory.initialize(Long.MAX_VALUE);
        }
    }

    @ParameterizedTest
    @EnumSource(SigningSchema.class)
    void everySignatureVerifiesAgainstTheRoster(final SigningSchema signingSchema) {
        final RosterWithKeys network = network(signingSchema);
        final List<PlatformEvent> events =
                EventStreamGenerator.generateSignedEvents(MIXED, network.roster(), COUNT, SEED, network.privateKeys());

        for (final PlatformEvent event : events) {
            final PublicKey publicKey = network.roster()
                    .rosterEntry(event.getCreatorId())
                    .gossipCaCertificate()
                    .getPublicKey();
            assertThat(CryptoUtils.verifySignature(event.getHash().getBytes(), event.getSignature(), publicKey))
                    .isTrue();
        }
    }

    @Test
    void unsignedEventsHaveEmptySignatures() {
        final List<PlatformEvent> events = EventStreamGenerator.generateUnsignedEvents(MIXED, ROSTER, COUNT, SEED);

        assertThat(events)
                .allSatisfy(event -> assertThat(event.getSignature().length()).isZero());
    }

    @Test
    void missingKeysAreRejected() {
        final RosterWithKeys network = network(SigningSchema.ED25519);
        final Map<NodeId, KeysAndCerts> keys = new HashMap<>(network.privateKeys());
        keys.remove(network.roster().nodeIdAtIndex(0));

        assertThatThrownBy(() -> EventStreamGenerator.generateSignedEvents(MIXED, network.roster(), 1, SEED, keys))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moreOtherParentsThanOtherNodesAreRejected() {
        final EventShape shape = new EventShape(constant(NUM_NODES), constant(0), constant(1));

        assertThatThrownBy(() -> EventStreamGenerator.generateUnsignedEvents(shape, ROSTER, 1, SEED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A roster whose node IDs are 1 to 3 apart, with the nodes' keys from {@code KeysAndCertsGenerator}. */
    private static RosterWithKeys network(final SigningSchema signingSchema) {
        return RosterWrapperFactory.randomRosterWithKeys(
                new Random(SEED), NUM_NODES, WeightGenerators.GAUSSIAN, signingSchema);
    }

    /** Checks that the first two lists hold the same events, and the third different ones. */
    private static void assertSameEvents(
            final List<PlatformEvent> first, final List<PlatformEvent> second, final List<PlatformEvent> otherSeed) {
        // A gossip event holds the event core, the parents, the transactions, and the signature.
        assertThat(gossipEvents(second)).isEqualTo(gossipEvents(first));
        assertThat(hashes(second)).isEqualTo(hashes(first));
        assertThat(gossipEvents(otherSeed)).isNotEqualTo(gossipEvents(first));
    }

    private static List<GossipEvent> gossipEvents(final List<PlatformEvent> events) {
        return events.stream().map(PlatformEvent::getGossipEvent).toList();
    }

    private static List<Hash> hashes(final List<PlatformEvent> events) {
        return events.stream().map(PlatformEvent::getHash).toList();
    }
}
