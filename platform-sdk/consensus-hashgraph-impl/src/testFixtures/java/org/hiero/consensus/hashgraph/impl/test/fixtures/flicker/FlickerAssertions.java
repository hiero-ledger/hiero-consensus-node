// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ListAssert;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Assertions over consensus output, stated in the names a graph uses.
 * <p>
 * Consensus output names events by content hash. A fixture names them {@code b2} and {@code c3}, so every test that
 * reads a snapshot or a round back has to resolve one to the other before it can say anything — which is the piece
 * these helpers share.
 */
public final class FlickerAssertions {

    private FlickerAssertions() {}

    /**
     * Assert on the judges of a snapshot's round, by name.
     * <p>
     * The assertion is returned rather than the expectation taken, so the caller states exactly as much as its
     * scenario requires:
     * <pre>
     * assertJudges(snapshot, graph)
     *         .as("round %d judges", ANCHOR_ROUND)
     *         .contains(J1, J2)
     *         .doesNotContain(MID);
     * </pre>
     * That matters for a fixture whose closure layers exist only to reach a decision: SCN-001 requires both ends of
     * its chain to be judges and the event between them not to be, and says nothing about the rest. Taking a fixed
     * set instead would tie the premise to layers that are edited for unrelated reasons.
     *
     * @param snapshot the snapshot whose judges to assert on
     * @param graph    the events the names come from
     * @return an assertion over the judges' names
     */
    @NonNull
    public static ListAssert<String> assertJudges(
            @NonNull final ConsensusSnapshot snapshot, @NonNull final List<NamedEvent> graph) {
        return assertThat(judgeNames(snapshot, graph)).as("round %d judges", snapshot.round());
    }

    /**
     * The judges of a snapshot's round, by name.
     *
     * @param snapshot the snapshot
     * @param graph    the events the names come from
     * @return the judges' names, in the snapshot's order
     */
    @NonNull
    public static List<String> judgeNames(
            @NonNull final ConsensusSnapshot snapshot, @NonNull final List<NamedEvent> graph) {
        final Map<Hash, String> names = namesByHash(graph);
        return snapshot.judgeIds().stream()
                .map(judgeId -> new Hash(judgeId.judgeHash(), DigestType.SHA_384))
                .map(hash -> name(names, hash))
                .toList();
    }

    /**
     * The graph's names, by content hash. This is the mapping anything reading consensus output needs, because
     * snapshots and {@code ConsensusRound}s carry hashes and nothing else.
     *
     * @param graph the events
     * @return each event's name, by hash
     */
    @NonNull
    public static Map<Hash, String> namesByHash(@NonNull final List<NamedEvent> graph) {
        final Map<Hash, String> names = new LinkedHashMap<>();
        for (final NamedEvent named : graph) {
            names.put(named.event().getHash(), named.name());
        }
        return names;
    }

    /**
     * A hash the graph does not contain is rendered rather than dropped: a judge that is not in the fixture is a
     * finding, and a silent gap in the list would read as the fixture being wrong about which events are judges.
     */
    @NonNull
    private static String name(@NonNull final Map<Hash, String> names, @NonNull final Hash hash) {
        final String name = names.get(hash);
        return name == null ? "not-in-graph:" + hash.toHex(6) : name;
    }
}
