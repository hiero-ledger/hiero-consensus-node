// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pragmatic parser for the YAML-frontmatter subset used across the consensus-layer KB. It is not a
 * general YAML implementation; it handles exactly what the KB uses: top-level scalars, flow lists
 * ({@code [a, b]}), block lists ({@code - item}), one level of nested maps whose values are scalars
 * or lists ({@code related:}), and folded/literal block scalars ({@code >} / {@code |}).
 */
public final class FrontmatterParser {

    /** Shared empty result: no values, no key lines, body starting at line 1. */
    private static final Frontmatter EMPTY = new Frontmatter(Map.of(), Map.of(), 1);

    /** Prevents instantiation of this static-only utility. */
    private FrontmatterParser() {}

    /**
     * Parses the leading frontmatter of a document given all of its lines (0-based list).
     *
     * @param lines all lines of the document, 0-indexed.
     * @return the parsed frontmatter, or an empty result if there is no valid {@code ---}-delimited
     *     frontmatter block.
     */
    public static Frontmatter parse(final List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return EMPTY;
        }
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return EMPTY;
        }

        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, Integer> keyLines = new LinkedHashMap<>();
        int i = 1;
        while (i < close) {
            final String line = lines.get(i);
            if (line.isBlank() || indent(line) > 0) {
                i++;
                continue;
            }
            final int colon = line.indexOf(':');
            if (colon < 0) {
                i++;
                continue;
            }
            final String key = line.substring(0, colon).strip();
            final String rest = line.substring(colon + 1).strip();
            keyLines.put(key, i + 1); // 1-based file line

            if (!rest.isEmpty() && !rest.equals(">") && !rest.equals("|")) {
                values.put(key, parseInline(rest));
                i++;
            } else if (rest.equals(">") || rest.equals("|")) {
                final StringBuilder block = new StringBuilder();
                i++;
                while (i < close && (lines.get(i).isBlank() || indent(lines.get(i)) > 0)) {
                    if (!lines.get(i).isBlank()) {
                        if (block.length() > 0) {
                            block.append(' ');
                        }
                        block.append(lines.get(i).strip());
                    }
                    i++;
                }
                values.put(key, block.toString());
            } else {
                // Empty inline value: look ahead for a block list or a nested map.
                i++;
                final List<String> childLines = new ArrayList<>();
                while (i < close && (lines.get(i).isBlank() || indent(lines.get(i)) > 0)) {
                    if (!lines.get(i).isBlank()) {
                        childLines.add(lines.get(i));
                    }
                    i++;
                }
                values.put(key, parseChildren(childLines));
            }
        }
        return new Frontmatter(values, keyLines, close + 2);
    }

    /**
     * Interprets the indented lines under a key with an empty inline value as either a block list
     * ({@code - item}) or a nested map ({@code key: value}).
     *
     * @param childLines the non-blank indented lines belonging to the key.
     * @return a {@code List<String>} for a block list, a {@code Map<String, Object>} for a nested
     *     map, or an empty string if there are no child lines.
     */
    private static Object parseChildren(final List<String> childLines) {
        if (childLines.isEmpty()) {
            return "";
        }
        if (childLines.get(0).strip().startsWith("- ")
                || childLines.get(0).strip().equals("-")) {
            final List<String> items = new ArrayList<>();
            for (final String c : childLines) {
                final String t = c.strip();
                if (t.startsWith("- ")) {
                    items.add(t.substring(2).strip());
                } else if (t.equals("-")) {
                    items.add("");
                }
            }
            return items;
        }
        // Nested map: key: value lines.
        final Map<String, Object> map = new LinkedHashMap<>();
        for (final String c : childLines) {
            final int colon = c.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String k = c.substring(0, colon).strip();
            final String v = c.substring(colon + 1).strip();
            map.put(k, parseInline(v));
        }
        return map;
    }

    /**
     * Parses an inline value: a flow list {@code [a, b]} or a scalar string.
     *
     * @param rest the trimmed text following the {@code key:} on a line.
     * @return a {@code List<String>} for a flow list, otherwise the unquoted scalar string.
     */
    private static Object parseInline(final String rest) {
        if (rest.startsWith("[") && rest.endsWith("]")) {
            final String inner = rest.substring(1, rest.length() - 1).strip();
            if (inner.isEmpty()) {
                return new ArrayList<String>();
            }
            final List<String> items = new ArrayList<>();
            for (final String part : inner.split(",")) {
                final String p = part.strip();
                if (!p.isEmpty()) {
                    items.add(p);
                }
            }
            return items;
        }
        return unquote(rest);
    }

    /**
     * Strips a single pair of matching surrounding double or single quotes, if present.
     *
     * @param s the scalar text.
     * @return the unquoted string, or {@code s} unchanged if it is not quoted.
     */
    private static String unquote(final String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Counts the number of leading space characters on a line.
     *
     * @param line the line to measure.
     * @return the count of leading spaces.
     */
    private static int indent(final String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
