// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphParser;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test that checks the {@code parse → write → parse} round trip.
 * <p>
 * This is the automatic detector for a lossy format. Every field that reaches an event's content hash has to survive
 * a trip through the text, or the suite has a field it can set and cannot store — and the failure would otherwise
 * surface as a fixture that reproduces differently once it has been exported and read back.
 */
class GraphWriterTest {

    /** Exercises every header the format has and every override a row can carry. */
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

    @Test
    @DisplayName("the text written out parses back to an equal graph")
    void roundTripIsLossless() {
        final FlickerGraph graph = GraphParser.parse(GRAPH);

        final FlickerGraph reparsed = GraphParser.parse(graph.toText());

        assertThat(reparsed)
                .as("a difference here is a field the format cannot carry: %s", graph.toText())
                .isEqualTo(graph);
    }

    @Test
    @DisplayName("the rebuilt graph has the same event hashes")
    void roundTripPreservesHashes() {
        final FlickerGraph graph = GraphParser.parse(GRAPH);
        final FlickerGraph reparsed = GraphParser.parse(graph.toText());

        assertThat(hashes(reparsed.build())).isEqualTo(hashes(graph.build()));
    }

    @Test
    @DisplayName("writing what was written changes nothing")
    void writingIsIdempotent() {
        final String once = GraphParser.parse(GRAPH).toText();

        assertThat(GraphParser.parse(once).toText()).isEqualTo(once);
    }

    @Test
    @DisplayName("both roster blocks survive, in declaration order")
    void bothRostersSurvive() {
        final FlickerGraph reparsed = GraphParser.parse(GraphParser.parse(GRAPH).toText());

        assertThat(reparsed.rosters().stream().map(NamedRoster::name)).containsExactly("initial", "after");
        assertThat(reparsed.roster("after").weightsByLetter())
                .containsExactly(Map.entry("a", 4L), Map.entry("b", 1L), Map.entry("c", 1L), Map.entry("d", 4L));
    }

    @Test
    @DisplayName("a value that can be recomputed is not written")
    void derivedValuesAreNotWritten() {
        final String text = GraphParser.parse(GRAPH).toText();

        assertThat(row(text, "a3")).as("an override is written").contains("br=4");
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
        final String text = GraphParser.parse(GRAPH).toText();

        assertThat(row(text, "a3")).isEqualTo("a3    a2    b1        br=4");
        assertThat(row(text, "a2"))
                .as("'none' rather than '-', because a row saying it carries no transactions should read as a count")
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

        assertThat(GraphParser.parse(text).toText())
                .as("a default equal to the standard one is noise, and the rows should start at the top")
                .isEqualTo("""
                        roster solo  a=1 b=3

                        #  name  self  other-parents
                           a0    -     -
                           b0    -     -
                           b1    b0    a0
                        """);
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
