// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

/**
 * Reads the {@code .graph} text format into a {@link FlickerGraph}.
 *
 * <pre>
 * kb SCN-001
 * config roundsNonAncient=3
 * defaults coin=0 txs=none
 * anchor 2
 *
 * roster initial   a=1 b=1 c=1 d=1
 * roster after     a=4 b=1 c=1 d=4
 *
 * #  name  self   other-parents
 *    a0    -      -
 *    b0    -      -
 *    c0    -      -
 *    d0    -      -
 *    a1    a0     b0
 *    b1    b0     a1,c0,d0   coin=1
 * </pre>
 *
 * <b>Rows are listed in creation order</b> — genesis first, so a parent is always defined <i>above</i> its children.
 * A reference to a row below is a parse error, which enforces the ordering and catches a typo in the same check. The
 * {@link FlickerGraph#specs()} this produces are the rows in file order, because that is the order events must be fed.
 * <p>
 * <b>The creator is not a column.</b> Every event's name starts with its creator's letter, which must be a node in the
 * graph's rosters, and a self-parent must start with the same letter, since it is the same creator's previous event.
 * <p>
 * <b>Every node is declared in a roster block, with its weight.</b> A node that creates no events does not appear in
 * the rows but still counts towards every supermajority, so the roster cannot be inferred from the rows.
 *
 * <h2>Format</h2>
 * A line is split on whitespace. {@code #} starts a comment that runs to the end of the line, and blank lines are
 * skipped. A line whose first word is one of the header keywords below is a header line, and any other line is an
 * event row. Header lines may appear anywhere, including between rows; the rows themselves must be in creation order.
 * <p>
 * <b>Header lines</b>
 * <ul>
 *     <li>{@code kb <id>}: the knowledge-base entry this graph reproduces, such as {@code SCN-001}.</li>
 *     <li>{@code config <key>=<value> ...}: consensus configuration keys the graph needs, kept as written.</li>
 *     <li>{@code defaults <key>=<value> ...}: the values every row gets unless it overrides them. The keys are
 *         {@code coin}, {@code br} and {@code txs}. With no {@code defaults} line they are
 *         {@code coin=0 br=1 txs=0}. Each can be overridden for a specific event (see below).</li>
 *     <li>{@code anchor <round>}: the round a test restarts from. The test feeds every row, takes this round's
 *         snapshot once the round is decided, starts a new consensus from that snapshot, and feeds every row again.
 *         The test chooses which roster applies before and after the restart, looking them up by name.</li>
 *     <li>{@code roster <name> <letter>=<weight> ...}: a roster, at least one per graph. Each name must be unique.
 *         A node is a single lower-case letter, and its weight is always written and is never negative. Every
 *         roster must list the same nodes, in any order, so rosters can reweight nodes but not add or remove
 *         them yet (this will be added later).</li>
 *     <li>{@code requires} is reserved, and is rejected until it is supported. It will later allow properties of the
 *     graph to be specific as requirements for the graph to be valid for its intended test.</li>
 * </ul>
 * <p>
 * <b>Event rows</b> have the form {@code <name> [<self-parent> [<other-parents>]] [<key>=<value> ...]}.
 * <ul>
 *     <li>{@code name}: unique in the graph, written in letters and digits such as {@code b4}. Its first letter is
 *         the creator.</li>
 *     <li>{@code self-parent}: the event's self parent, or {@code -} for none.</li>
 *     <li>{@code other-parents}: comma-separated with no spaces, or {@code -} for none. Omitted trailing columns
 *         also mean none.</li>
 *     <li>{@code coin=<n>}: the event's coin value.</li>
 *     <li>{@code br=<n>}: the event's birth round. If it is not set, an event with parents takes the highest birth
 *         round among them, and an event without parents takes the default. If it is set, it must not be below the
 *         highest birth round of all parents.</li>
 *     <li>{@code txs=<n>}: the number of transactions, where {@code none} or {@code -} means 0.</li>
 * </ul>
 * Overrides come after the parents, and only {@code coin}, {@code br} and {@code txs} are currently supported.
 */
public final class GraphParser {

    private static final String NONE = "-";

    private GraphParser() {}

    /**
     * Parse a {@code .graph} file.
     *
     * @param text the file's contents
     * @return the parsed graph
     * @throws GraphParseException if the text is not a well-formed graph; every message names the offending line
     */
    @NonNull
    public static FlickerGraph parse(@NonNull final String text) {
        String kb = null;
        Long anchor = null;
        GraphDefaults defaults = GraphDefaults.STANDARD;
        final Map<String, String> config = new LinkedHashMap<>();
        final List<NamedRoster> rosters = new ArrayList<>();
        final Map<String, Integer> rosterLines = new HashMap<>();
        final List<Row> rows = new ArrayList<>();

        final String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            final int lineNumber = i + 1;
            final String line = stripComment(lines[i]);
            if (line.isEmpty()) {
                continue;
            }
            final List<String> tokens = List.of(line.split("\\s+"));
            final String keyword = tokens.getFirst();

            switch (keyword) {
                case "kb" -> {
                    requireArgs(lineNumber, tokens, 2, "kb <id>");
                    kb = tokens.get(1);
                }
                case "anchor" -> {
                    requireArgs(lineNumber, tokens, 2, "anchor <round>");
                    anchor = parseLong(lineNumber, tokens.get(1), "anchor round");
                    if (anchor < 1) {
                        throw new GraphParseException(lineNumber, "anchor round must be at least 1");
                    }
                }
                case "config" -> config.putAll(keyValues(lineNumber, tokens.subList(1, tokens.size())));
                case "defaults" ->
                    defaults = applyDefaults(
                            lineNumber, defaults, keyValues(lineNumber, tokens.subList(1, tokens.size())));
                case "roster" -> {
                    final NamedRoster roster = parseRoster(lineNumber, tokens);
                    final Integer previous = rosterLines.putIfAbsent(roster.name(), lineNumber);
                    if (previous != null) {
                        throw new GraphParseException(
                                lineNumber,
                                ("roster '%s' is already declared on line %d. Rosters are looked up by name, so a "
                                                + "second block with the same name would never be read.")
                                        .formatted(roster.name(), previous));
                    }
                    rosters.add(roster);
                }
                case "requires" ->
                    throw new GraphParseException(
                            lineNumber,
                            "'requires' is not supported yet. It will name the properties a fixture depends on, "
                                    + "and until something exists to resolve them, an entry could only be accepted "
                                    + "without being checked.");
                default -> rows.add(parseRow(lineNumber, tokens));
            }
        }

        if (rosters.isEmpty()) {
            throw new GraphParseException(lines.length, "no roster declared; a graph needs at least one");
        }
        requireSameMembership(rosters, rosterLines);
        if (rows.isEmpty()) {
            throw new GraphParseException(lines.length, "no events declared");
        }

        return new FlickerGraph(kb, config, defaults, anchor, rosters, toSpecs(rows, rosters.getFirst(), defaults));
    }

    /**
     * Turn the rows into specs, validating as it goes.
     *
     * @param rows     the rows, in file order
     * @param first    the roster a creator letter must be a node in; every roster has the same nodes
     * @param defaults supplies the birth round of an event with no parents
     * @return the specs, parents first
     */
    @NonNull
    private static List<EventSpec> toSpecs(
            @NonNull final List<Row> rows, @NonNull final NamedRoster first, @NonNull final GraphDefaults defaults) {
        final List<String> letters = first.letters();
        final Map<String, Integer> lineByName = new HashMap<>();
        for (final Row row : rows) {
            final Integer previous = lineByName.putIfAbsent(row.name(), row.line());
            if (previous != null) {
                throw new GraphParseException(
                        row.line(),
                        "'%s' is already defined on line %d. A duplicate name silently rewires every later parent "
                                        .formatted(row.name(), previous)
                                + "reference to the wrong event.");
            }
        }

        final List<EventSpec> specs = new ArrayList<>(rows.size());
        final Set<String> defined = new LinkedHashSet<>();
        for (final Row row : rows) {
            final String letter = row.name().substring(0, 1);
            if (!letters.contains(letter)) {
                throw new GraphParseException(
                        row.line(),
                        "'%s' starts with '%s', which is not a node in roster '%s' (%s). An event's creator is the "
                                        .formatted(row.name(), letter, first.name(), letters)
                                + "first letter of its name.");
            }
            if (row.selfParent() != null && !row.selfParent().startsWith(letter)) {
                throw new GraphParseException(
                        row.line(),
                        ("'%s' names '%s' as its self-parent, but a self-parent is the same creator's previous "
                                        + "event, so its name must also start with '%s'. Consensus would treat '%s' as "
                                        + "an other-parent.")
                                .formatted(row.name(), row.selfParent(), letter, row.selfParent()));
            }

            for (final String parent : row.parents()) {
                if (!defined.contains(parent)) {
                    throw new GraphParseException(
                            row.line(),
                            lineByName.containsKey(parent)
                                    ? "'%s' names parent '%s', which is defined below it on line %d. Rows are listed "
                                                    .formatted(row.name(), parent, lineByName.get(parent))
                                            + "in creation order, so a parent must be written above its child."
                                    : "'%s' names parent '%s', which this graph never defines"
                                            .formatted(row.name(), parent));
                }
            }
            defined.add(row.name());

            specs.add(new EventSpec(
                    row.name(),
                    row.selfParent(),
                    row.otherParents(),
                    row.coin(),
                    row.birthRound(),
                    row.transactionCount()));
        }

        try {
            GraphBuilder.deriveBirthRounds(specs, defaults.birthRound());
        } catch (final GraphBuilder.BirthRoundViolation e) {
            throw new GraphParseException(lineByName.get(e.eventName()), e.getMessage());
        }
        return specs;
    }

    /**
     * Parse one event row: {@code name [self [other,other]] [key=value ...]}.
     */
    @NonNull
    private static Row parseRow(final int line, @NonNull final List<String> tokens) {
        final List<String> positional = new ArrayList<>();
        final Map<String, String> overrides = new LinkedHashMap<>();
        for (final String token : tokens.subList(1, tokens.size())) {
            if (token.indexOf('=') >= 0) {
                final String[] parts = token.split("=", 2);
                overrides.put(parts[0], parts[1]);
            } else if (!overrides.isEmpty()) {
                throw new GraphParseException(
                        line,
                        "'%s' comes after an override; parents must be written before key=value".formatted(token));
            } else {
                positional.add(token);
            }
        }
        if (positional.size() > 2) {
            throw new GraphParseException(
                    line,
                    "expected at most a self-parent and a comma-separated other-parent list, but found %d columns: %s"
                            .formatted(positional.size(), positional));
        }

        final String selfParent = positional.isEmpty() ? null : nullIfNone(positional.getFirst());
        final List<String> otherParents = positional.size() < 2
                ? List.of()
                : nullIfNone(positional.get(1)) == null
                        ? List.of()
                        : List.of(positional.get(1).split(","));

        Long coin = null;
        Long birthRound = null;
        Integer transactionCount = null;
        for (final Map.Entry<String, String> override : overrides.entrySet()) {
            switch (override.getKey()) {
                case "coin" -> coin = parseLong(line, override.getValue(), "coin");
                case "br" -> birthRound = parseLong(line, override.getValue(), "br");
                case "txs" -> transactionCount = parseTransactionCount(line, override.getValue());
                default ->
                    throw new GraphParseException(
                            line, "unknown override '%s'; a row may set coin, br or txs".formatted(override.getKey()));
            }
        }
        return new Row(line, tokens.getFirst(), selfParent, otherParents, coin, birthRound, transactionCount);
    }

    @NonNull
    private static NamedRoster parseRoster(final int line, @NonNull final List<String> tokens) {
        if (tokens.size() < 3) {
            throw new GraphParseException(line, "expected: roster <name> <letter>=<weight> ...");
        }
        final SequencedMap<String, Long> weights = new LinkedHashMap<>();
        for (final String token : tokens.subList(2, tokens.size())) {
            final int equals = token.indexOf('=');
            final String letter = equals < 0 ? token : token.substring(0, equals);
            if (letter.length() != 1 || letter.charAt(0) < 'a' || letter.charAt(0) > 'z') {
                throw new GraphParseException(
                        line, "'%s' is not a node letter; use a single lower-case letter".formatted(token));
            }
            if (equals < 0) {
                // Every weight is written out, including 1, so a roster reads without knowing what an omitted
                // weight would mean.
                throw new GraphParseException(
                        line,
                        "'%s' has no weight; weights are always explicit, so write '%s=1'".formatted(letter, letter));
            }
            final long weight = parseLong(line, token.substring(equals + 1), "weight of '%s'".formatted(letter));
            if (weight < 0) {
                throw new GraphParseException(line, "'%s' has a negative weight".formatted(letter));
            }
            if (weights.put(letter, weight) != null) {
                throw new GraphParseException(line, "'%s' is listed twice in this roster".formatted(letter));
            }
        }
        return new NamedRoster(tokens.get(1), weights);
    }

    /**
     * Only reweighting is supported: every roster must list the same nodes. Adding or removing a node would feed
     * consensus events whose creator is not in the current roster, which this suite does not support yet, so the
     * parser refuses such a graph.
     *
     * @param rosters     every roster block, in declaration order
     * @param rosterLines the line each roster was declared on, by name
     */
    private static void requireSameMembership(
            @NonNull final List<NamedRoster> rosters, @NonNull final Map<String, Integer> rosterLines) {
        final NamedRoster first = rosters.getFirst();
        for (final NamedRoster other : rosters.subList(1, rosters.size())) {
            if (!Set.copyOf(other.letters()).equals(Set.copyOf(first.letters()))) {
                throw new GraphParseException(
                        rosterLines.get(other.name()),
                        ("roster '%s' (%s) does not have the same nodes as '%s' (%s). Only reweighting is "
                                        + "supported: adding or removing a node between rosters is not supported "
                                        + "yet.")
                                .formatted(other.name(), other.letters(), first.name(), first.letters()));
            }
        }
    }

    @NonNull
    private static GraphDefaults applyDefaults(
            final int line, @NonNull GraphDefaults defaults, @NonNull final Map<String, String> values) {
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            defaults = switch (entry.getKey()) {
                case "coin" -> defaults.withCoin(parseLong(line, entry.getValue(), "coin"));
                case "br" -> defaults.withBirthRound(parseLong(line, entry.getValue(), "br"));
                case "txs" -> defaults.withTransactionCount(parseTransactionCount(line, entry.getValue()));
                default ->
                    throw new GraphParseException(
                            line, "unknown default '%s'; a header may set coin, br or txs".formatted(entry.getKey()));
            };
        }
        return defaults;
    }

    @NonNull
    private static Map<String, String> keyValues(final int line, @NonNull final List<String> tokens) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String token : tokens) {
            final int equals = token.indexOf('=');
            if (equals < 1 || equals == token.length() - 1) {
                throw new GraphParseException(line, "expected key=value, but found '%s'".formatted(token));
            }
            values.put(token.substring(0, equals), token.substring(equals + 1));
        }
        return values;
    }

    private static int parseTransactionCount(final int line, @NonNull final String value) {
        if (NONE.equals(value) || "none".equals(value)) {
            return 0;
        }
        final long count = parseLong(line, value, "txs");
        if (count < 0) {
            throw new GraphParseException(line, "txs cannot be negative");
        }
        return (int) count;
    }

    private static long parseLong(final int line, @NonNull final String value, @NonNull final String what) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new GraphParseException(line, "%s must be a number, but was '%s'".formatted(what, value));
        }
    }

    private static void requireArgs(
            final int line, @NonNull final List<String> tokens, final int expected, @NonNull final String usage) {
        if (tokens.size() != expected) {
            throw new GraphParseException(line, "expected: " + usage);
        }
    }

    @Nullable
    private static String nullIfNone(@NonNull final String token) {
        return NONE.equals(token) ? null : token;
    }

    @NonNull
    private static String stripComment(@NonNull final String line) {
        final int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).trim();
    }

    /** One parsed row, before its parents are checked. */
    private record Row(
            int line,
            @NonNull String name,
            @Nullable String selfParent,
            @NonNull List<String> otherParents,
            @Nullable Long coin,
            @Nullable Long birthRound,
            @Nullable Integer transactionCount) {

        @NonNull
        List<String> parents() {
            final List<String> all = new ArrayList<>(otherParents.size() + 1);
            if (selfParent != null) {
                all.add(selfParent);
            }
            all.addAll(otherParents);
            return all;
        }
    }
}
