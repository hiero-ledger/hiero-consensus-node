// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.EventSpec;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphParseException;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphParser;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The {@code .graph} text format and its parser. */
class GraphParserTest {

    private static final String GRAPH = """
            kb SCN-TEST
            config roundsNonAncient=3
            defaults coin=0 txs=none
            anchor 2

            roster initial   a=1 b=1 c=1 d=1
            roster after     a=4 b=1 c=1 d=4

            #  name  self   other-parents
               a0    -      -
               b0    -      -
               c0    -      -
               d0    -      -
               a1    a0     b0
               b1    b0     a0,c0,d0   coin=1
               a2    a1     b1
            """;

    @Nested
    @DisplayName("a well-formed graph")
    class WellFormed {

        @Test
        @DisplayName("header values are carried through")
        void headerIsParsed() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            assertThat(graph.kb()).isEqualTo("SCN-TEST");
            assertThat(graph.config()).containsExactly(java.util.Map.entry("roundsNonAncient", "3"));
            assertThat(graph.anchorRound()).isEqualTo(2L);
            assertThat(graph.defaults().coin()).isZero();
            assertThat(graph.defaults().transactionCount()).isZero();
            assertThat(graph.defaults().birthRound()).isEqualTo(1L);
        }

        @Test
        @DisplayName("both rosters are read, in declaration order")
        void rostersAreParsed() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            assertThat(graph.rosters().stream().map(NamedRoster::name)).containsExactly("initial", "after");
            assertThat(graph.roster("after").weightsByLetter())
                    .containsExactly(
                            java.util.Map.entry("a", 4L),
                            java.util.Map.entry("b", 1L),
                            java.util.Map.entry("c", 1L),
                            java.util.Map.entry("d", 4L));
        }

        @Test
        @DisplayName("rows are read in creation order")
        void rowsAreReadInFeedOrder() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            assertThat(graph.specs().stream().map(EventSpec::name))
                    .as("the file is written genesis-first, so specs come back in file order")
                    .containsExactly("a0", "b0", "c0", "d0", "a1", "b1", "a2");
        }

        @Test
        @DisplayName("the creator is taken from the first letter of the name")
        void creatorComesFromTheName() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            assertThat(spec(graph, "a2").creator()).isEqualTo("a");
            assertThat(spec(graph, "b1").creator()).isEqualTo("b");
            assertThat(spec(graph, "d0").creator()).isEqualTo("d");
        }

        @Test
        @DisplayName("parents are read, with '-' meaning none")
        void parentsAreParsed() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            assertThat(spec(graph, "b1").selfParent()).isEqualTo("b0");
            assertThat(spec(graph, "b1").otherParents()).containsExactly("a0", "c0", "d0");
            assertThat(spec(graph, "a0").selfParent()).isNull();
            assertThat(spec(graph, "a0").otherParents()).isEmpty();
        }

        @Test
        @DisplayName("a row override is carried, and leaves other rows on the default")
        void overridesAreParsed() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            assertThat(spec(graph, "b1").coin()).isEqualTo(1L);
            assertThat(spec(graph, "a1").coin())
                    .as("not overridden, so it takes the header default")
                    .isNull();
        }

        @Test
        @DisplayName("GraphBuilder accepts what the parser produces")
        void theGraphBuilds() {
            final FlickerGraph graph = GraphParser.parse(GRAPH);

            final List<NamedEvent> events = graph.build();

            assertThat(events.stream().map(NamedEvent::name)).containsExactly("a0", "b0", "c0", "d0", "a1", "b1", "a2");
            assertThat(events.getLast().event().getCreatorId())
                    .as("a2's creator id comes from its letter")
                    .isEqualTo(NamedRoster.nodeIdFor("a"));
        }

        @Test
        @DisplayName("the example in GraphParser's javadoc parses")
        void javadocExampleParses() {
            // A copy of the example in GraphParser's class javadoc; update both together.
            final String example = """
                    kb SCN-001
                    config roundsNonAncient=3
                    defaults coin=0 txs=none
                    anchor 2

                    roster initial   a=1 b=1 c=1 d=1
                    roster after     a=4 b=1 c=1 d=4

                    #  name  self   other-parents
                       a0    -      -
                       b0    -      -
                       c0    -      -
                       d0    -      -
                       a1    a0     b0
                       b1    b0     a1,c0,d0   coin=1
                    """;

            assertThat(GraphParser.parse(example).specs()).hasSize(6);
        }

        @Test
        @DisplayName("rosters may list the same nodes in a different order")
        void rosterOrderMayDiffer() {
            final FlickerGraph graph = GraphParser.parse(
                    GRAPH.replace("roster after     a=4 b=1 c=1 d=4", "roster after     d=4 c=1 b=1 a=4"));

            assertThat(graph.roster("after").letters()).containsExactly("d", "c", "b", "a");
        }
    }

    @Nested
    @DisplayName("a malformed graph fails, naming the line")
    class Malformed {

        @Test
        @DisplayName("a creator letter that is not in the roster")
        void unknownCreatorLetter() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   a2    a1     b1", "   x2    -      -")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 16")
                    .hasMessageContaining("'x'");
        }

        @Test
        @DisplayName("a parent written below its child")
        void parentDefinedBelow() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   a1    a0     b0", "   a1    a0     a2")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 14")
                    .hasMessageContaining("defined below it");
        }

        @Test
        @DisplayName("a self-parent created by a different node")
        void selfParentFromAnotherCreator() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   a2    a1     b1", "   a2    b1     a1")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 16")
                    .hasMessageContaining("must also start with 'a'");
        }

        @Test
        @DisplayName("a parent that does not exist")
        void unknownParent() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   a1    a0     b0", "   a1    a0     z9")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 14")
                    .hasMessageContaining("never defines");
        }

        @Test
        @DisplayName("a duplicate event name")
        void duplicateName() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   c0    -      -", "   a0    -      -")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 12")
                    .hasMessageContaining("already defined on line 10");
        }

        @Test
        @DisplayName("an unknown row override")
        void unknownOverride() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   a1    a0     b0", "   a1    a0     b0  wat=1")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 14")
                    .hasMessageContaining("unknown override");
        }

        @Test
        @DisplayName("a birth round below the event's parents, which is INV-011")
        void birthRoundBelowParents() {
            assertThatThrownBy(() -> GraphParser.parse(replaceRow("   a1    a0     b0", "   a1    a0     b0  br=0")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 14")
                    .hasMessageContaining("INV-011");
        }

        @Test
        @DisplayName("a requires block, which is not supported yet")
        void requiresIsDeferred() {
            assertThatThrownBy(() -> GraphParser.parse(GRAPH + "requires witness(b1)\n"))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 17")
                    .hasMessageContaining("not supported yet");
        }

        @Test
        @DisplayName("a roster that changes membership rather than only weights")
        void membershipChangeIsRefused() {
            assertThatThrownBy(() -> GraphParser.parse(
                            GRAPH.replace("roster after     a=4 b=1 c=1 d=4", "roster after     a=4 b=1 c=1 e=4")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 7")
                    .hasMessageContaining("Only reweighting is supported");
        }

        @Test
        @DisplayName("two rosters with the same name")
        void duplicateRosterName() {
            assertThatThrownBy(() -> GraphParser.parse(
                            GRAPH.replace("roster after     a=4 b=1 c=1 d=4", "roster initial   a=4 b=1 c=1 d=4")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 7")
                    .hasMessageContaining("already declared on line 6");
        }

        @Test
        @DisplayName("a node listed without a weight")
        void bareLetterIsRefused() {
            assertThatThrownBy(() -> GraphParser.parse(
                            GRAPH.replace("roster initial   a=1 b=1 c=1 d=1", "roster initial   a b c d")))
                    .isInstanceOf(GraphParseException.class)
                    .hasMessageContaining("line 6")
                    .hasMessageContaining("write 'a=1'");
        }

        @Test
        @DisplayName("no roster at all")
        void noRoster() {
            assertThatThrownBy(() -> GraphParser.parse("""
                    kb X
                       a0    -      -
                    """))
                    .isInstanceOf(GraphParseException.class)
                    .as("the error is reported at the end of the file, where the missing roster was noticed")
                    .hasMessageContaining("line 3")
                    .hasMessageContaining("no roster");
        }
    }

    private static EventSpec spec(final FlickerGraph graph, final String name) {
        return graph.specs().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String replaceRow(final String row, final String replacement) {
        assertThat(GRAPH).contains(row);
        return GRAPH.replace(row, replacement);
    }
}
