// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PublicKey;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.event.validation.DefaultEventFieldValidator;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.transaction.TransactionLimits;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

/**
 * Checks that {@link EventStreamGenerator} gives the same events for the same seed, events that pass internal
 * validation, and signatures that verify against the roster, made with the keys of {@link KeysAndCertsGenerator}.
 */
class EventStreamGeneratorTest {

    private static final int NUM_NODES = 4;
    private static final int COUNT = 1_000;
    private static final long SEED = 7;

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
        final List<PlatformEvent> events = EventStreamGenerator.generate(shape, NUM_NODES, null, COUNT, SEED)
                .events();

        assertThat(events).hasSize(COUNT);
        for (final PlatformEvent event : events) {
            assertThat(event.getSelfParent()).isEqualTo(event.getAllParents().getFirst());
            assertThat(event.getSelfParent().creator()).isEqualTo(event.getCreatorId());
            assertThat(event.getOtherParents()).hasSize(2);
            assertThat(event.getAllParents().stream().map(EventDescriptorWrapper::creator))
                    .doesNotHaveDuplicates();
            assertThat(event.getBirthRound()).isEqualTo(EventStreamGenerator.BIRTH_ROUND);
            assertThat(event.getAllParents())
                    .allSatisfy(parent -> assertThat(parent.birthRound()).isLessThanOrEqualTo(event.getBirthRound()));
            assertThat(event.getTransactions()).hasSize(3).allSatisfy(transaction -> assertThat(transaction.getSize())
                    .isEqualTo(445));
            assertThat(event.getHash()).isNotNull();
            assertThat(event.getOrigin()).isEqualTo(EventOrigin.GOSSIP);
        }
        assertThat(events.stream().map(PlatformEvent::getCreatorId).distinct()).hasSize(NUM_NODES);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(SigningSchema.class)
    void sameSeedGivesSameEvents(@Nullable final SigningSchema signingSchema) {
        final List<PlatformEvent> first = EventStreamGenerator.generate(MIXED, NUM_NODES, signingSchema, COUNT, SEED)
                .events();
        final List<PlatformEvent> second = EventStreamGenerator.generate(MIXED, NUM_NODES, signingSchema, COUNT, SEED)
                .events();
        final List<PlatformEvent> otherSeed = EventStreamGenerator.generate(
                        MIXED, NUM_NODES, signingSchema, COUNT, SEED + 1)
                .events();

        // A gossip event holds the event core, the parents, the transactions, and the signature.
        assertThat(gossipEvents(second)).isEqualTo(gossipEvents(first));
        assertThat(hashes(second)).isEqualTo(hashes(first));
        assertThat(gossipEvents(otherSeed)).isNotEqualTo(gossipEvents(first));
    }

    static Stream<Named<EventShape>> shapes() {
        return Stream.of(
                Named.of("mixed", MIXED),
                Named.of("one transaction", new EventShape(constant(1), constant(1), constant(445))),
                Named.of("no transaction", new EventShape(constant(1), constant(0), constant(445))));
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void everyEventPassesFieldValidation(final EventShape shape) {
        final DefaultEventFieldValidator validator =
                new DefaultEventFieldValidator(new NoOpMetrics(), Time.getCurrent(), TRANSACTION_LIMITS);

        final List<PlatformEvent> events = EventStreamGenerator.generate(shape, NUM_NODES, null, COUNT, SEED)
                .events();

        assertThat(events).allMatch(validator::isValid);
    }

    @Test
    void cutoverToSha256() {
        final DefaultEventFieldValidator validator =
                new DefaultEventFieldValidator(new NoOpMetrics(), Time.getCurrent(), TRANSACTION_LIMITS);
        EventHashFactory.initialize(0);
        try {
            final List<PlatformEvent> events = EventStreamGenerator.generate(MIXED, NUM_NODES, null, COUNT, SEED)
                    .events();

            assertThat(events).allSatisfy(event -> {
                assertThat(event.getHash().getDigestType()).isEqualTo(DigestType.SHA_256);
                assertThat(event.getAllParents())
                        .allSatisfy(parent ->
                                assertThat(parent.hash().getDigestType()).isEqualTo(DigestType.SHA_256));
            });
            assertThat(events).allMatch(validator::isValid);
        } finally {
            // the default, so that the other tests hash with SHA-384
            EventHashFactory.initialize(Long.MAX_VALUE);
        }
    }

    @ParameterizedTest
    @EnumSource(SigningSchema.class)
    void everySignatureVerifiesAgainstTheRoster(final SigningSchema signingSchema) {
        final EventStream stream = EventStreamGenerator.generate(MIXED, NUM_NODES, signingSchema, COUNT, SEED);

        for (final PlatformEvent event : stream.events()) {
            final PublicKey publicKey = stream.roster()
                    .rosterEntry(event.getCreatorId())
                    .gossipCaCertificate()
                    .getPublicKey();
            assertThat(CryptoUtils.verifySignature(event.getHash().getBytes(), event.getSignature(), publicKey))
                    .isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(SigningSchema.class)
    void keysComeFromKeysAndCertsGenerator(final SigningSchema signingSchema) throws Exception {
        final EventStream stream = EventStreamGenerator.generate(MIXED, NUM_NODES, signingSchema, 0, SEED);

        assertThat(stream.keys()).hasSize(NUM_NODES);
        for (final RosterEntryWrapper entry : stream.roster().rosterEntries()) {
            final byte[] expected = KeysAndCertsGenerator.generate(entry.nodeId(), signingSchema)
                    .sigKeyPair()
                    .getPublic()
                    .getEncoded();
            assertThat(stream.keys()
                            .get(entry.nodeId())
                            .sigKeyPair()
                            .getPublic()
                            .getEncoded())
                    .isEqualTo(expected);
            assertThat(entry.gossipCaCertificate().getPublicKey().getEncoded()).isEqualTo(expected);
        }
    }

    @Test
    void unsignedEventsHaveNoKeysAndEmptySignatures() {
        final EventStream stream = EventStreamGenerator.generate(MIXED, NUM_NODES, null, COUNT, SEED);

        assertThat(stream.keys()).isEmpty();
        assertThat(stream.roster().rosterEntries())
                .allSatisfy(entry -> assertThat(entry.gossipCaCertificate()).isNull());
        assertThat(stream.events())
                .allSatisfy(event -> assertThat(event.getSignature().length()).isZero());
    }

    @Test
    void moreOtherParentsThanOtherNodesAreRejected() {
        final EventShape shape = new EventShape(constant(NUM_NODES), constant(0), constant(1));

        assertThatThrownBy(() -> EventStreamGenerator.generate(shape, NUM_NODES, null, 1, SEED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<GossipEvent> gossipEvents(final List<PlatformEvent> events) {
        return events.stream().map(PlatformEvent::getGossipEvent).toList();
    }

    private static List<Hash> hashes(final List<PlatformEvent> events) {
        return events.stream().map(PlatformEvent::getHash).toList();
    }
}
