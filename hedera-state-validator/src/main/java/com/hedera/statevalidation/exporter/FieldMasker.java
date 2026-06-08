// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Removes a set of configured fields from a value JSON document so that two values can be
 * compared while disregarding fields that are expected to differ.
 *
 * <p>Paths use a dotted notation with an explicit array wildcard:
 * <ul>
 *   <li>{@code accountId.accountNum} - a nested object field</li>
 *   <li>{@code transfers[*].amount} - the {@code amount} field of every element of the
 *       {@code transfers} array</li>
 *   <li>{@code tokens[*]} - every element of the {@code tokens} array (the array is emptied)</li>
 *   <li>{@code matrix[*][*]} - every element of every sub-array (nested wildcards)</li>
 * </ul>
 *
 * <p>A path segment that does not exist in a given document is silently ignored, so the same
 * masker can be applied uniformly to values of different shapes. Masking never fails on a
 * structural mismatch (e.g. a wildcard applied to a non-array node); the mismatching segment is
 * simply skipped.
 *
 * <p>This implementation uses {@code org.json}, which is already on the dependency graph, to
 * avoid introducing a JSON binding library. {@link JSONObject#similar(Object)} provides an
 * order-independent structural comparison, which is used to decide whether two masked values are
 * equal.
 *
 * <p>This class is immutable after construction and therefore safe to share across threads. Each
 * {@link #maskedValue(String)} call parses a fresh tree, so concurrent calls do not interfere.
 */
public final class FieldMasker {

    /** Each entry is the parsed segment list for one configured ignore path. */
    private final List<List<Segment>> paths;

    private FieldMasker(@NonNull final List<List<Segment>> paths) {
        this.paths = paths;
    }

    /**
     * Builds a masker from a list of dotted ignore paths. Returns {@code null} when the list is
     * {@code null} or empty, which lets callers cheaply detect the "no masking configured" case
     * and keep the fast byte-level comparison path.
     *
     * @param ignoreFields the configured ignore paths; may be {@code null} or empty
     * @return a masker, or {@code null} if there is nothing to mask
     */
    public static FieldMasker fromPaths(final List<String> ignoreFields) {
        if (ignoreFields == null || ignoreFields.isEmpty()) {
            return null;
        }
        final List<List<Segment>> parsed = new ArrayList<>(ignoreFields.size());
        for (final String raw : ignoreFields) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            parsed.add(parse(raw.trim()));
        }
        return parsed.isEmpty() ? null : new FieldMasker(parsed);
    }

    /**
     * Returns {@code true} if the two value JSON documents are structurally equal once all
     * configured ignore-fields have been removed from each side. The comparison is
     * order-independent (object field order does not matter).
     *
     * @param sourceJson the first value JSON
     * @param targetJson the second value JSON
     * @return whether the two values are equal after masking
     */
    public boolean equalAfterMasking(@NonNull final String sourceJson, @NonNull final String targetJson) {
        final Object source = maskedValue(sourceJson);
        final Object target = maskedValue(targetJson);
        return similar(source, target);
    }

    /**
     * Parses a JSON document and removes all configured fields, returning the pruned root. The
     * returned object is a {@link JSONObject}, a {@link JSONArray}, or a scalar, depending on the
     * top-level JSON type.
     *
     * @param json the value JSON as produced by the value codec
     * @return the pruned root object
     */
    Object maskedValue(@NonNull final String json) {
        final String trimmed = json.trim();
        final Object root;
        if (trimmed.startsWith("[")) {
            root = new JSONArray(trimmed);
        } else if (trimmed.startsWith("{")) {
            root = new JSONObject(trimmed);
        } else {
            // Scalar value: there is nothing to mask.
            return trimmed;
        }
        for (final List<Segment> path : paths) {
            applyPath(root, path, 0);
        }
        return root;
    }

    /**
     * Recursively walks {@code node} following the path segments. When the final segment is
     * reached, the addressed field or array element(s) are removed.
     */
    private void applyPath(final Object node, final List<Segment> path, final int index) {
        if (node == null || index >= path.size()) {
            return;
        }
        final Segment segment = path.get(index);
        final boolean last = index == path.size() - 1;

        if (segment.wildcard()) {
            if (!(node instanceof JSONArray array)) {
                return;
            }
            if (last) {
                // Remove every element of the array (iterate downward to keep indices valid).
                for (int i = array.length() - 1; i >= 0; i--) {
                    array.remove(i);
                }
            } else {
                for (int i = 0; i < array.length(); i++) {
                    applyPath(array.opt(i), path, index + 1);
                }
            }
        } else {
            if (!(node instanceof JSONObject object)) {
                return;
            }
            if (last) {
                object.remove(segment.field());
            } else {
                applyPath(object.opt(segment.field()), path, index + 1);
            }
        }
    }

    /** Order-independent structural equality across {@link JSONObject}, {@link JSONArray}, and scalars. */
    private static boolean similar(final Object a, final Object b) {
        if (a instanceof JSONObject ao && b instanceof JSONObject bo) {
            return ao.similar(bo);
        }
        if (a instanceof JSONArray aa && b instanceof JSONArray ba) {
            return aa.similar(ba);
        }
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Splits a dotted path into ordered segments. A field followed by one or more {@code [*]}
     * wildcards expands into a field segment plus one wildcard segment per {@code [*]}. For
     * example {@code transfers[*].amount} becomes {@code transfers}, {@code [*]}, {@code amount}.
     */
    private static List<Segment> parse(final String path) {
        final List<Segment> segments = new ArrayList<>();
        for (final String dotPart : path.split("\\.")) {
            if (dotPart.isEmpty()) {
                continue;
            }
            // Count trailing [*] wildcards, e.g. matrix[*][*] -> field "matrix" + 2 wildcards.
            String field = dotPart;
            int wildcards = 0;
            while (field.endsWith("[*]")) {
                wildcards++;
                field = field.substring(0, field.length() - 3);
            }
            if (!field.isEmpty()) {
                segments.add(Segment.ofField(field));
            }
            for (int i = 0; i < wildcards; i++) {
                segments.add(Segment.ofWildcard());
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Invalid ignore-field path: '" + path + "'");
        }
        return segments;
    }

    /** A single path step: either a named object field or an array wildcard. */
    private record Segment(String field, boolean wildcard) {
        static Segment ofField(final String name) {
            return new Segment(name, false);
        }

        static Segment ofWildcard() {
            return new Segment(null, true);
        }
    }
}
