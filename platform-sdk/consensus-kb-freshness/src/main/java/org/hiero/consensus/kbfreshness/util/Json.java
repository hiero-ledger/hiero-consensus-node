// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.util;

import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free, deterministic JSON writer. Object keys are written in insertion order;
 * callers use ordered maps and sorted lists so output is byte-stable across runs. Only the value
 * types this tool emits are supported: {@link String}, {@link Number}, {@link Boolean}, {@code null},
 * {@link Map} (String-keyed), and {@link List}.
 */
public final class Json {

    /** Prevents instantiation of this utility class. */
    private Json() {}

    /**
     * Serializes the given value to a deterministic, newline-terminated JSON string.
     *
     * @param value the value to serialize (String, Number, Boolean, null, String-keyed Map, or List).
     * @return the JSON text, terminated by a trailing newline.
     */
    public static String write(final Object value) {
        final StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Dispatches a single value to the writer for its type.
     *
     * @param sb the buffer to append to.
     * @param v the value to write.
     * @param indent the current indentation level.
     */
    private static void writeValue(final StringBuilder sb, final Object v, final int indent) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b.toString());
            case Number n -> sb.append(n.toString());
            case Map<?, ?> m -> writeObject(sb, m, indent);
            case List<?> l -> writeArray(sb, l, indent);
            default ->
                throw new IllegalArgumentException(
                        "Unsupported JSON value type: " + v.getClass().getName());
        }
    }

    /**
     * Writes a JSON object, one entry per line, in the map's iteration order.
     *
     * @param sb the buffer to append to.
     * @param m the map to write; keys are coerced to strings.
     * @param indent the current indentation level.
     */
    private static void writeObject(final StringBuilder sb, final Map<?, ?> m, final int indent) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        final int child = indent + 1;
        int i = 0;
        for (final Map.Entry<?, ?> e : m.entrySet()) {
            indent(sb, child);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), child);
            if (++i < m.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    /**
     * Writes a JSON array, one element per line.
     *
     * @param sb the buffer to append to.
     * @param l the list to write.
     * @param indent the current indentation level.
     */
    private static void writeArray(final StringBuilder sb, final List<?> l, final int indent) {
        if (l.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        final int child = indent + 1;
        for (int i = 0; i < l.size(); i++) {
            indent(sb, child);
            writeValue(sb, l.get(i), child);
            if (i < l.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    /**
     * Appends two spaces per indentation level.
     *
     * @param sb the buffer to append to.
     * @param level the indentation level.
     */
    private static void indent(final StringBuilder sb, final int level) {
        sb.append("  ".repeat(level));
    }

    /**
     * Writes a JSON string literal, escaping quotes, backslashes, and control characters.
     *
     * @param sb the buffer to append to.
     * @param s the string to write.
     */
    private static void writeString(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
