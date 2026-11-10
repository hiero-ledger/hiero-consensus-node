// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphDefaults;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphParser;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphWriter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test that checks the {@code write → parse} round trip.
 * <p>
 * Every field that reaches an event's content hash has to survive a trip through the text, or the suite has a field
 * it can set and cannot store. The round trip starts from a graph built in code rather than from parsed text, because
 * parsed text can only hold fields the format already has syntax for. It catches a lost field only if
 * {@link #everyField()} sets it, so a new hashed field in {@link EventSpec} needs adding there too.
 */
class GraphWriterTest {

    /** Text with every header the format has and every override a row can carry, for checking the layout. */
    private static final String GRAPH = """
            kb SCN-TEST
            config roundsNonAncient=3
            defaults coin=3 br=2 txs=1
            anchor 2

            roster initial  a=1 b=1 c=1 d=1
            roster after    a=4 b=1 c=1 d=4

            #  name  self   other-parents
               a0    -      -
               b0    -      -
               c0    -      -
               d0    -      -
               a1    a0     b0
               b1    b0     a0,c0,d0
               a2    a1     b1          coin=1 txs=none
               a3    a2     b1          br=4
            """;

    /**
     * A graph built in code that sets every header field and every per-event field, including a birth round equal to
     * the one its parents give it.
     */
    private static FlickerGraph everyField() {
        return new FlickerGraph(
                "SCN-TEST",
                Map.of("roundsNonAncient", "3"),
                new GraphDefaults(3, 2, 1),
                2L,
                List.of(roster("initial", 1, 1, 1, 1), roster("after", 4, 1, 1, 4)),
                List.of(
                        EventSpec.genesis("a0"),
                        EventSpec.genesis("b0"),
                        EventSpec.genesis("c0"),
                        EventSpec.genesis("d0"),
                        EventSpec.event("a1", "a0", "b0"),
                        EventSpec.event("b1", "b0", "a0", "c0", "d0"),
                        EventSpec.event("a2", "a1", "b1").withCoin(1).withTransactionCount(0),
                        EventSpec.event("a3", "a2", "b1").withBirthRound(4),
                        EventSpec.event("b2", "b1", "a1").withBirthRound(2),
                        EventSpec.event("c1", "c0", "a1").withTransactionCount(2)));
    }

    @Test
    @DisplayName("the text written out parses back to an equal graph")
    void roundTripIsLossless() {
        final FlickerGraph graph = everyField();
        final String text = GraphWriter.write(graph);

        assertThat(GraphParser.parse(text))
                .as("a difference here is a field the format cannot carry: %s", text)
                .isEqualTo(graph);
    }

    @Test
    @DisplayName("the rebuilt graph has the same event hashes")
    void roundTripPreservesHashes() {
        final FlickerGraph graph = everyField();
        final FlickerGraph reparsed = GraphParser.parse(GraphWriter.write(graph));

        assertThat(hashes(reparsed.build())).isEqualTo(hashes(graph.build()));
    }

    @Test
    @DisplayName("writing what was written changes nothing")
    void writingIsIdempotent() {
        final String once = GraphWriter.write(GraphParser.parse(GRAPH));

        assertThat(GraphWriter.write(GraphParser.parse(once))).isEqualTo(once);
    }

    @Test
    @DisplayName("both roster blocks survive, in declaration order")
    void bothRostersSurvive() {
        final FlickerGraph reparsed = GraphParser.parse(GraphWriter.write(GraphParser.parse(GRAPH)));

        assertThat(reparsed.rosters().stream().map(NamedRoster::name)).containsExactly("initial", "after");
        assertThat(reparsed.roster("after").weightsByLetter())
                .containsExactly(Map.entry("a", 4L), Map.entry("b", 1L), Map.entry("c", 1L), Map.entry("d", 4L));
    }

    @Test
    @DisplayName("a value that can be recomputed is not written")
    void derivedValuesAreNotWritten() {
        final String text = GraphWriter.write(GraphParser.parse(GRAPH));

        assertThat(row(text, "a3")).as("an override is written").contains("br=4");
        assertThat(row(GraphWriter.write(everyField()), "b2"))
                .as("an explicit birth round is written even when it equals the derived one")
                .contains("br=2");
        assertThat(row(text, "a2"))
                .as("a2's birth round derives from its parents, and writing it would let the file contradict itself")
                .doesNotContain("br=");
        assertThat(row(text, "b1"))
                .as("a row that changes no default carries no overrides")
                .isEqualTo("b1    b0    a0,c0,d0");
        assertThat(text)
                .as("creators are carried by the names, not by a column")
                .doesNotContain("creator");
    }

    @Test
    @DisplayName("overrides line up in a column of their own")
    void overridesLineUp() {
        final String text = GraphWriter.write(GraphParser.parse(GRAPH));

        assertThat(row(text, "a3")).isEqualTo("a3    a2    b1        br=4");
        assertThat(row(text, "a2"))
                .as("zero transactions is written as 'none', which reads more plainly than '-'")
                .isEqualTo("a2    a1    b1        coin=1 txs=none");
    }

    @Test
    @DisplayName("a header the graph never set is not written")
    void unsetHeadersAreNotWritten() {
        final String text = """
                roster solo  a=1 b=3

                   a0    -     -
                   b0    -     -
                   b1    b0    a0
                """;

        assertThat(GraphWriter.write(GraphParser.parse(text)))
                .as("a default equal to the standard one is not written, so the file starts with the roster block")
                .isEqualTo("""
                        roster solo  a=1 b=3

                        #  name  self  other-parents
                           a0    -     -
                           b0    -     -
                           b1    b0    a0
                        """);
    }

    private static NamedRoster roster(final String name, final long a, final long b, final long c, final long d) {
        final SequencedMap<String, Long> weights = new LinkedHashMap<>();
        weights.put("a", a);
        weights.put("b", b);
        weights.put("c", c);
        weights.put("d", d);
        return new NamedRoster(name, weights);
    }

    private static List<Hash> hashes(final List<NamedEvent> events) {
        return events.stream().map(named -> named.event().getHash()).toList();
    }

    private static String row(final String text, final String name) {
        return text.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(name + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for '%s' in:%n%s".formatted(name, text)));
    }
}
