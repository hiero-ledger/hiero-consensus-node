// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;

/**
 * Turns a list of {@link EventSpec} into built events, in order.
 * <p>
 * Specs must be listed bottom-up: an event may only name parents defined above it in the list. That is also the order
 * the events must be fed to {@link FlickerIntake}, so the returned list is the feed order. Note this is the reverse of
 * a {@code .graph} file's row order, which reads like the hashgraph diagram with genesis at the bottom;
 * {@code GraphParser} does the reversal.
 * <p>
 * <b>Four fields are specified on every event</b> rather than left to {@link TestingEventBuilder}'s defaults. All four
 * are hashed content, and hashes feed the final tie-break in consensus order, so a random value means a fixture that
 * silently orders differently between runs:
 * <ul>
 *     <li><b>birth round</b> — the builder's default is {@code max(parent birth rounds) + random(0..2)}, whose random
 *         term would drift the ancient window between runs. Derived here as {@code max(parent birth rounds)}, or
 *         {@link GraphDefaults#birthRound()} for an event with no parents. A spec may override it upward but never
 *         below its parents', which is INV-011 — and is checked here, because
 *         {@code DefaultEventFieldValidator} enforces it in the validation stage, which this harness does not run.
 *         Deriving rather than pinning matters: the ancient threshold is the minimum judge birth round, so a graph
 *         whose events all sit in round one can never make anything ancient.</li>
 *     <li><b>time created</b> — the default advances a random 1-99ms from the <i>self-parent</i>, drawn from the
 *         supplied {@link Random}. That satisfies {@code ConsensusLinker}'s self-parent ordering check either way, so
 *         the pinning is not about validity; it is about reproducibility and derivability. Event timestamps feed round
 *         timestamps and the consensus order a fixture asserts, and a value drawn from the shared {@code Random} shifts
 *         for every later event when a row is inserted. Derived instead from the self-parent by a fixed
 *         {@value #TIME_STEP_MILLIS}ms step, with a genesis event offset by its creator index. A timestamp therefore
 *         reads as <i>height in its own column, plus who made it</i> — {@code a0} is the base, {@code a1} is +100ms,
 *         {@code b0} is +1ms — and the two components cannot collide while a graph has fewer creators than the step.
 *         Deriving from the self-parent rather than from list position is what bounds the effect of an edit. A
 *         timestamp then depends on the creator's own chain and nothing else, so inserting an event perturbs exactly
 *         the events that <i>descend</i> from it — which a structural edit was always going to change — rather than
 *         everything below the insertion point in the file. {@code nGen} is structural in the same way
 *         ({@code max(parents) + 1}) and the other hashed fields are constants, so the descendant set is the whole
 *         blast radius.</li>
 *     <li><b>transactions</b> — the default is two random 4-byte app transactions. Taken instead from
 *         {@link GraphDefaults#transactionCount()}, with content derived from the event's name so that it reproduces.
 *         The count is not only a hash input: {@code EventUtils.getLastTransTime} reads it to set the minimum
 *         consensus timestamp of the next event.</li>
 *     <li><b>coin</b> — the default is drawn from the supplied {@link Random}. That makes every hash in the graph
 *         depend on the exact <i>sequence</i> of builder calls rather than on the seed alone: insert one row anywhere
 *         and every later event draws a different coin, and therefore hashes differently. Taken instead from
 *         {@link GraphDefaults#coin()}, which is what lets a graph be edited without disturbing the events around
 *         the edit.</li>
 * </ul>
 * With all four specified, nothing the builder still draws from the {@code Random} — the signature and the placeholder
 * hash — reaches the content hash, so the seed no longer determines any hash in the graph.
 * <p>
 * <b>Events are hashed as they are built</b>, before being used as anyone's parent. {@code TestingEventBuilder}
 * installs a random placeholder hash and takes a parent's descriptor from whatever hash the parent carries at the
 * time, while both intake paths overwrite that placeholder with the real content hash. Without hashing here, every
 * child would claim a parent hash that no longer resolves and the orphan buffer would hold the whole graph back.
 */
public final class GraphBuilder {

    private static final EventHasher HASHER = new DefaultEventHasher();
    private static final Instant BASE_TIME = Instant.ofEpochSecond(1_700_000_000L);
    private static final long TIME_STEP_MILLIS = 100;

    private GraphBuilder() {}

    /**
     * Build the events a graph specifies, taking {@link GraphDefaults#STANDARD}.
     *
     * @param specs  the events, bottom-up; each may only name parents defined above it
     * @param random the source of randomness; no longer reaches any hash, but still fills the signature
     * @param roster the roster whose node ids the {@code creator} indices refer to
     * @return the built events in the same order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build(
            @NonNull final List<EventSpec> specs, @NonNull final Random random, @NonNull final RosterWrapper roster) {
        return build(specs, random, roster, GraphDefaults.STANDARD);
    }

    /**
     * Build the events a graph specifies.
     *
     * @param specs    the events, bottom-up; each may only name parents defined above it
     * @param random   the source of randomness; no longer reaches any hash, but still fills the signature
     * @param roster   the roster whose node ids the {@code creator} indices refer to
     * @param defaults the per-event fields a spec does not override
     * @return the built events in the same order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build(
            @NonNull final List<EventSpec> specs,
            @NonNull final Random random,
            @NonNull final RosterWrapper roster,
            @NonNull final GraphDefaults defaults) {

        final List<NodeId> nodeIds = roster.nodeIds();
        final Map<String, Long> birthRounds = deriveBirthRounds(specs, defaults.birthRound());
        final Map<String, PlatformEvent> builtByName = new LinkedHashMap<>();
        final List<NamedEvent> ordered = new ArrayList<>(specs.size());

        for (final EventSpec spec : specs) {
            if (spec.creator() < 0 || spec.creator() >= nodeIds.size()) {
                throw new IllegalArgumentException("'%s' names creator index %d, but the roster has %d nodes"
                        .formatted(spec.name(), spec.creator(), nodeIds.size()));
            }
            if (builtByName.containsKey(spec.name())) {
                throw new IllegalArgumentException("'%s' is defined twice".formatted(spec.name()));
            }

            final PlatformEvent selfParent =
                    spec.selfParent() == null ? null : resolve(builtByName, spec.selfParent(), spec.name());

            final TestingEventBuilder builder = new TestingEventBuilder(random)
                    .setCreatorId(nodeIds.get(spec.creator()))
                    .setBirthRound(birthRounds.get(spec.name()))
                    .setTransactionBytes(transactions(
                            spec.name(),
                            spec.transactionCount() == null ? defaults.transactionCount() : spec.transactionCount()))
                    .setCoin(spec.coin() == null ? defaults.coin() : spec.coin())
                    .setTimeCreated(timeCreated(selfParent, spec.creator()));

            if (selfParent != null) {
                builder.setSelfParent(selfParent);
            }
            if (!spec.otherParents().isEmpty()) {
                builder.setOtherParents(spec.otherParents().stream()
                        .map(parent -> resolve(builtByName, parent, spec.name()))
                        .toList());
            }

            final PlatformEvent event = HASHER.hashEvent(builder.build());
            builtByName.put(spec.name(), event);
            ordered.add(new NamedEvent(spec.name(), event));
        }
        return List.copyOf(ordered);
    }

    /**
     * Work out the birth round of every event in a graph.
     * <p>
     * This is a pure function of the specs — no consensus run, no algorithm dependency — which is what lets a
     * {@code .graph} file state birth rounds as frozen text rather than as a directive evaluated at load time.
     *
     * @param specs          the events, bottom-up
     * @param genesisDefault the birth round of an event with no parents
     * @return each event's birth round, by name
     * @throws IllegalArgumentException if an override is below the event's parents, which violates INV-011
     */
    @NonNull
    public static Map<String, Long> deriveBirthRounds(@NonNull final List<EventSpec> specs, final long genesisDefault) {

        final Map<String, Long> birthRounds = new LinkedHashMap<>();
        for (final EventSpec spec : specs) {
            long derived = Long.MIN_VALUE;
            for (final String parent : spec.allParents()) {
                final Long parentBirthRound = birthRounds.get(parent);
                if (parentBirthRound != null) {
                    derived = Math.max(derived, parentBirthRound);
                }
            }
            if (derived == Long.MIN_VALUE) {
                derived = genesisDefault;
            }

            if (spec.birthRound() != null) {
                if (spec.birthRound() < derived) {
                    throw new BirthRoundViolation(
                            spec.name(),
                            ("'%s' sets birth round %d, below the %d its parents already carry. INV-011 requires a "
                                            + "birth round at or above every parent's, and production's event "
                                            + "validator would reject this event before consensus saw it.")
                                    .formatted(spec.name(), spec.birthRound(), derived));
                }
                derived = spec.birthRound();
            }
            birthRounds.put(spec.name(), derived);
        }
        return birthRounds;
    }

    /**
     * Thrown when a spec sets a birth round below its parents'. Carries the offending event's name so that a caller
     * holding line numbers — {@code GraphParser} — can point at the row rather than repeat the derivation.
     */
    public static class BirthRoundViolation extends IllegalArgumentException {
        private final String eventName;

        /**
         * @param eventName the event whose birth round is too low
         * @param message   what is wrong with it
         */
        public BirthRoundViolation(@NonNull final String eventName, @NonNull final String message) {
            super(message);
            this.eventName = eventName;
        }

        /**
         * @return the event whose birth round is too low
         */
        @NonNull
        public String eventName() {
            return eventName;
        }
    }

    /**
     * The time an event is created: one fixed step after its self-parent, or the base time offset by the creator's
     * index if it has none.
     *
     * @param selfParent the event's self-parent, or null if it is a genesis event
     * @param creator    the creator's index into the roster
     * @return the time created
     */
    @NonNull
    private static Instant timeCreated(@Nullable final PlatformEvent selfParent, final int creator) {
        return selfParent == null
                ? BASE_TIME.plusMillis(creator)
                : selfParent.getTimeCreated().plusMillis(TIME_STEP_MILLIS);
    }

    /**
     * Transaction payloads for an event, derived from its name so that they are the same on every run and move only
     * when the event itself is renamed.
     *
     * @param name  the event's name
     * @param count how many transactions it carries
     * @return the payloads
     */
    @NonNull
    private static List<Bytes> transactions(@NonNull final String name, final int count) {
        final List<Bytes> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int value = name.hashCode() * 31 + i;
            payloads.add(Bytes.wrap(
                    new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}));
        }
        return payloads;
    }

    @NonNull
    private static PlatformEvent resolve(
            @NonNull final Map<String, PlatformEvent> builtByName,
            @NonNull final String parent,
            @NonNull final String child) {

        final PlatformEvent event = builtByName.get(parent);
        if (event == null) {
            throw new IllegalArgumentException(
                    "'%s' names parent '%s', which is not defined above it".formatted(child, parent));
        }
        return event;
    }
}
