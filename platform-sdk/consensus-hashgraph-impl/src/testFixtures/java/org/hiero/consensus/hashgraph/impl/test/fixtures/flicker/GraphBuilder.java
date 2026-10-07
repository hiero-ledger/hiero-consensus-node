// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * the events must be fed to {@link FlickerIntake}, so the returned list is the feed order.
 * <p>
 * <b>Three fields are pinned on every event</b> rather than left to {@link TestingEventBuilder}'s defaults. All three
 * are hashed content, and hashes feed the final tie-break in consensus order, so a random value means a fixture that
 * silently orders differently between runs:
 * <ul>
 *     <li><b>birth round</b> — the default is {@code max(parent birth rounds) + random(0..2)}, which would drift the
 *         ancient window. Pinned to 1, so nothing in a small graph goes ancient.</li>
 *     <li><b>time created</b> — the default advances from the <i>self-parent</i> only, so an other-parent lower in the
 *         graph can end up with a later timestamp than its child. {@code ConsensusLinker} silently refuses to link a
 *         parent whose {@code timeCreated} is at or after the child's, which would quietly change the graph. Pinned to
 *         advance per event in list order, which is why a parent is always strictly earlier than its child.</li>
 *     <li><b>transactions</b> — the default is two random 4-byte app transactions. Pinned to none.</li>
 * </ul>
 * The {@code coin} field is still drawn from the supplied {@link Random} — {@code TestingEventBuilder} has no setter
 * for it — so a fixed seed is what makes a graph reproducible.
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
     * Build the events a graph specifies.
     *
     * @param specs  the events, bottom-up; each may only name parents defined above it
     * @param random the source of randomness; supply a fixed seed for a reproducible graph
     * @param roster the roster whose node ids the {@code creator} indices refer to
     * @return the built events in the same order, each paired with its name
     */
    @NonNull
    public static List<NamedEvent> build(
            @NonNull final List<EventSpec> specs, @NonNull final Random random, @NonNull final RosterWrapper roster) {

        final List<NodeId> nodeIds = roster.nodeIds();
        final Map<String, PlatformEvent> builtByName = new LinkedHashMap<>();
        final List<NamedEvent> ordered = new ArrayList<>(specs.size());

        int index = 0;
        for (final EventSpec spec : specs) {
            if (spec.creator() < 0 || spec.creator() >= nodeIds.size()) {
                throw new IllegalArgumentException("'%s' names creator index %d, but the roster has %d nodes"
                        .formatted(spec.name(), spec.creator(), nodeIds.size()));
            }
            if (builtByName.containsKey(spec.name())) {
                throw new IllegalArgumentException("'%s' is defined twice".formatted(spec.name()));
            }

            final TestingEventBuilder builder = new TestingEventBuilder(random)
                    .setCreatorId(nodeIds.get(spec.creator()))
                    .setBirthRound(1)
                    .setTransactionBytes(List.of())
                    .setTimeCreated(BASE_TIME.plusMillis(TIME_STEP_MILLIS * index++));

            if (spec.selfParent() != null) {
                builder.setSelfParent(resolve(builtByName, spec.selfParent(), spec.name()));
            }
            if (spec.otherParents().length > 0) {
                builder.setOtherParents(Arrays.stream(spec.otherParents())
                        .map(parent -> resolve(builtByName, parent, spec.name()))
                        .toList());
            }

            final PlatformEvent event = HASHER.hashEvent(builder.build());
            builtByName.put(spec.name(), event);
            ordered.add(new NamedEvent(spec.name(), event));
        }
        return List.copyOf(ordered);
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
