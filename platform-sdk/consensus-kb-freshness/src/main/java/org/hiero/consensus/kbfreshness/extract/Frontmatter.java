// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.util.List;
import java.util.Map;

/**
 * Parsed YAML frontmatter of a KB document, restricted to the shapes the KB actually uses: scalars,
 * flow/block lists, and one level of nested maps of lists (e.g. {@code related:}). Values are
 * {@link String}, {@code List<String>}, or {@code Map<String, Object>}.
 *
 * @param values    top-level key to value.
 * @param keyLines  top-level key to the 1-based file line it appears on (for occurrence line hints).
 * @param bodyLine  the 1-based line where the body begins (just after the closing {@code ---}); 1 if
 *                  there is no frontmatter.
 */
public record Frontmatter(Map<String, Object> values, Map<String, Integer> keyLines, int bodyLine) {

    /**
     * Returns the value of {@code key} if it is a scalar string, otherwise {@code null}.
     *
     * @param key the top-level frontmatter key.
     * @return the scalar string value, or {@code null} if absent or not a string.
     */
    public String scalar(final String key) {
        return values.get(key) instanceof String s ? s : null;
    }

    /**
     * Returns the value of {@code key} as a list of strings, or an empty list if it is absent or not
     * a list.
     *
     * @param key the top-level frontmatter key.
     * @return the string list value, or an empty list.
     */
    @SuppressWarnings("unchecked")
    public List<String> list(final String key) {
        final Object v = values.get(key);
        if (v instanceof List<?> l) {
            return (List<String>) l;
        }
        return List.of();
    }

    /**
     * Returns the nested map at {@code parent}, or an empty map if it is absent or not a map.
     *
     * @param parent the top-level frontmatter key holding a nested map.
     * @return the nested map, or an empty map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nestedMap(final String parent) {
        if (values.get(parent) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /**
     * Returns the 1-based file line where {@code key} appears, or {@link #bodyLine()} if the key is
     * unknown.
     *
     * @param key the top-level frontmatter key.
     * @return the 1-based line of the key, or the body line as a fallback.
     */
    public int lineOf(final String key) {
        return keyLines.getOrDefault(key, bodyLine);
    }
}
