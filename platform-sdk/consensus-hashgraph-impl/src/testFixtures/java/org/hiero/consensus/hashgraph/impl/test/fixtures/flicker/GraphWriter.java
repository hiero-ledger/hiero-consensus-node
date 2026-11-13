// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link FlickerGraph} back out as {@code .graph} text. The inverse of {@link GraphParser}.
 * <p>
 * <b>Nothing outside the tests uses this yet.</b> The round-trip test uses it to check that every field that reaches
 * an event's hash can be written as text and read back.
 * <p>
 * <b>A value the reader can recompute is left out.</b> Three are:
 * <ul>
 *     <li>who created an event — the row has no column for it, because the name already says so: {@code b4} is
 *         created by node {@code b}.</li>
 *     <li>a birth round the event derives from its parents. A row writes {@code br=} only if its spec sets the birth
 *         round explicitly, which it is written for even when it equals the derived value.</li>
 *     <li>a default the graph never changed, which the header would otherwise restate from
 *         {@link GraphDefaults#STANDARD}.</li>
 * </ul>
 */
public final class GraphWriter {

    private static final String NONE = "-";
    private static final String GAP = "  ";
    private static final String INDENT = "   ";

    private GraphWriter() {}

    /**
     * Write a graph as {@code .graph} text.
     *
     * @param graph the graph
     * @return text that parses back to an equal graph, for any graph {@link GraphParser} accepts
     */
    @NonNull
    public static String write(@NonNull final FlickerGraph graph) {
        final StringBuilder out = new StringBuilder();

        if (graph.kb() != null) {
            line(out, "kb " + graph.kb());
        }
        if (!graph.config().isEmpty()) {
            line(out, "config " + keyValues(graph.config()));
        }
        final String defaults = defaultsLine(graph.defaults());
        if (!defaults.isEmpty()) {
            line(out, "defaults " + defaults);
        }
        if (graph.anchorRound() != null) {
            line(out, "anchor " + graph.anchorRound());
        }
        if (!out.isEmpty()) {
            out.append('\n');
        }

        final int rosterNameWidth =
                width(graph.rosters().stream().map(NamedRoster::name).toList(), 0);
        for (final NamedRoster roster : graph.rosters()) {
            line(out, "roster " + pad(roster.name(), rosterNameWidth) + GAP + weights(roster));
        }
        out.append('\n');

        writeRows(out, graph);
        return out.toString();
    }

    /**
     * Write the event rows, with the columns padded to line up and a comment naming them.
     * <p>
     * Rows come out in the order of {@link FlickerGraph#specs()}, which is creation order, so that a parent is always
     * written above its children.
     */
    private static void writeRows(@NonNull final StringBuilder out, @NonNull final FlickerGraph graph) {
        final List<Row> rows = new ArrayList<>(graph.specs().size());
        for (final EventSpec spec : graph.specs()) {
            rows.add(new Row(
                    spec.name(),
                    spec.selfParent() == null ? NONE : spec.selfParent(),
                    spec.otherParents().isEmpty() ? NONE : String.join(",", spec.otherParents()),
                    overrides(spec)));
        }

        final int nameWidth = width(rows.stream().map(Row::name).toList(), "name".length());
        final int selfWidth = width(rows.stream().map(Row::self).toList(), "self".length());
        final int othersWidth = width(rows.stream().map(Row::others).toList(), 0);
        line(out, "#" + GAP + pad("name", nameWidth) + GAP + pad("self", selfWidth) + GAP + "other-parents");
        for (final Row row : rows) {
            line(
                    out,
                    INDENT
                            + pad(row.name(), nameWidth)
                            + GAP
                            + pad(row.self(), selfWidth)
                            + GAP
                            + (row.overrides().isEmpty()
                                    ? row.others()
                                    : pad(row.others(), othersWidth) + GAP + row.overrides()));
        }
    }

    /**
     * Only the defaults a graph actually changed, so that the header states what is unusual rather than restating
     * {@link GraphDefaults#STANDARD}.
     */
    @NonNull
    private static String defaultsLine(@NonNull final GraphDefaults defaults) {
        final List<String> parts = new ArrayList<>(3);
        if (defaults.coin() != GraphDefaults.STANDARD.coin()) {
            parts.add("coin=" + defaults.coin());
        }
        if (defaults.birthRound() != GraphDefaults.STANDARD.birthRound()) {
            parts.add("br=" + defaults.birthRound());
        }
        if (defaults.transactionCount() != GraphDefaults.STANDARD.transactionCount()) {
            parts.add("txs=" + defaults.transactionCount());
        }
        return String.join(" ", parts);
    }

    @NonNull
    private static String overrides(@NonNull final EventSpec spec) {
        final List<String> parts = new ArrayList<>(3);
        if (spec.coin() != null) {
            parts.add("coin=" + spec.coin());
        }
        if (spec.birthRound() != null) {
            parts.add("br=" + spec.birthRound());
        }
        if (spec.transactionCount() != null) {
            parts.add("txs=" + (spec.transactionCount() == 0 ? "none" : spec.transactionCount()));
        }
        return String.join(" ", parts);
    }

    @NonNull
    private static String weights(@NonNull final NamedRoster roster) {
        final List<String> parts = new ArrayList<>(roster.weightsByLetter().size());
        roster.weightsByLetter().forEach((letter, weight) -> parts.add(letter + "=" + weight));
        return String.join(" ", parts);
    }

    @NonNull
    private static String keyValues(@NonNull final Map<String, String> values) {
        final List<String> parts = new ArrayList<>(values.size());
        values.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join(" ", parts);
    }

    private static int width(@NonNull final List<String> values, final int atLeast) {
        return Math.max(atLeast, values.stream().mapToInt(String::length).max().orElse(0));
    }

    @NonNull
    private static String pad(@NonNull final String value, final int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    /** Appends a line with its trailing padding removed, so that alignment never leaves whitespace at line ends. */
    private static void line(@NonNull final StringBuilder out, @NonNull final String text) {
        out.append(text.stripTrailing()).append('\n');
    }

    /** One row's cells, already rendered. */
    private record Row(
            @NonNull String name,
            @NonNull String self,
            @NonNull String others,
            @NonNull String overrides) {}
}
