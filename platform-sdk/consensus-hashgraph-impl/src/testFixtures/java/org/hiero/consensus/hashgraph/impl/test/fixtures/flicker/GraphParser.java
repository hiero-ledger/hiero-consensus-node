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
 *    c0    -      -
 *    b3    b2
 *    b4    b3     a3,d3
 *    c3    c2     b4
 * </pre>
 *
 * <b>Rows are listed in creation order</b> — genesis first, so a parent is always defined <i>above</i> its children.
 * A reference to a row below is a parse error, which enforces the ordering and catches a typo in the same check. The
 * {@link FlickerGraph#specs()} this produces are the rows in file order, because that is the order events must be fed.
 *
 * <p>
 * <b>The creator is not a column.</b> Every event's name starts with its creator's letter, which must be a node in
 * the graph's rosters, and a letter always names the same node. That is the convention the hand-written fixtures already follow, and it keeps the file
 * using structural names while a test names roles: the file says {@code b4}, the test calls it {@code MID}. Role
 * names in the file would have to change whenever the graph was edited.
 *
 * <p>
 * <b>Roster membership is declared, never deduced.</b> A roster block is required even when a fixture does not argue about
 * weights, because a node that is in the roster and creates no events is a legitimate shape and is invisible in the
 * rows. Inferring the roster from the letters that appear would silently drop such a node and shrink the denominator
 * every supermajority is measured against, which changes what consensus decides. Weights are written out for the
 * same reason: an equal-weight roster is a fact the derivations argue about, and reading it off the page beats
 * knowing what an omitted weight defaults to.
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
                case "nodes" ->
                    throw new GraphParseException(
                            lineNumber,
                            "'nodes' was replaced by named roster blocks, so that weights sit beside the derivation "
                                    + "that argues about them. Write: roster initial   a=1 b=1 c=1 d=1");
                default -> rows.add(parseRow(lineNumber, tokens));
            }
        }

        if (rosters.isEmpty()) {
            throw new GraphParseException(lines.length, "no roster declared; a graph needs at least one");
        }
        requireSameMembership(rosters);
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
            throw new GraphParseException(lineByName.getOrDefault(e.eventName(), 0), e.getMessage());
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
                // Every weight is written out, including 1: a roster is an input the derivations argue about, so it
                // should read without knowing what an omitted weight means.
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
     * Only reweighting is supported. The syntax for membership change costs nothing — a second block simply
     * lists different letters — but what consensus does with an event whose creator is not in the current roster is
     * not established, so the parser refuses rather than producing something undefined.
     */
    private static void requireSameMembership(@NonNull final List<NamedRoster> rosters) {
        final NamedRoster first = rosters.getFirst();
        for (final NamedRoster other : rosters.subList(1, rosters.size())) {
            if (!Set.copyOf(other.letters()).equals(Set.copyOf(first.letters()))) {
                throw new GraphParseException(
                        0,
                        ("roster '%s' (%s) does not have the same nodes as '%s' (%s). Only "
                                        + "reweighting is supported: one DAG is replayed under both rosters, so a "
                                        + "removed node's events are still in the replay and an added node has none, "
                                        + "and that path is not yet established.")
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

    /** One parsed row, before its creator has been resolved. */
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
