// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * One place a collapsed finding's target is cited within its entry. Metadata only — no line affects
 * identity, and a finding closes only when its last occurrence resolves.
 *
 * @param docLine   the 1-based line in the KB file where the citation appears.
 * @param citedLine the cited code line number, or {@link Anchor#NO_LINE} if none.
 * @param rawText   the verbatim citation text.
 */
public record Occurrence(int docLine, int citedLine, String rawText) implements Comparable<Occurrence> {

    /**
     * Orders occurrences by {@code docLine}, then {@code citedLine}, then {@code rawText}, for a
     * stable sort.
     *
     * @param o the occurrence to compare against.
     * @return a negative integer, zero, or a positive integer as this occurrence is less than, equal
     *     to, or greater than the given one.
     */
    @Override
    public int compareTo(final Occurrence o) {
        int c = Integer.compare(docLine, o.docLine);
        if (c == 0) {
            c = Integer.compare(citedLine, o.citedLine);
        }
        if (c == 0) {
            c = rawText.compareTo(o.rawText);
        }
        return c;
    }
}
