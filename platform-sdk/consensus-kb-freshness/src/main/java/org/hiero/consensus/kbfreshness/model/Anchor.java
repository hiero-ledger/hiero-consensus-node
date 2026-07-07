// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * One raw citation of code extracted from a KB entry, before collapsing. Multiple anchors sharing
 * the same {@code (entry, target, kind)} collapse into a single {@link Finding} whose occurrences
 * are these anchors' line hints.
 *
 * @param kind        what kind of reference this is (also the check applied).
 * @param target      the normalized subject of the check — the exact question asked. For a class,
 *                    its simple (or qualified) name; for a path, the path; for a catalog ID, the ID.
 *                    Cited line numbers are never part of the target (invariant 3).
 * @param citedModule the module the citation scopes to, or {@code null} if none is cited. Used to
 *                    distinguish "gone" from "moved to another module".
 * @param citedScope  the enclosing scope for a member (e.g. the class owning a cited method), or
 *                    {@code null}.
 * @param docLine     the 1-based line in the KB file where this citation appears (for navigation).
 * @param citedLine   the cited code line number, or {@link #NO_LINE} if none. A navigation hint only
 *                    — never asserted on; drives auto-fix proposals when the symbol moved.
 * @param rawText     the verbatim citation text, for evidence and diagnostics.
 */
public record Anchor(
        AnchorKind kind,
        String target,
        String citedModule,
        String citedScope,
        int docLine,
        int citedLine,
        String rawText) {

    /** Sentinel {@code citedLine} value meaning no code line was cited. */
    public static final int NO_LINE = -1;

    /**
     * The occurrence view of this anchor (drops the resolution-only fields).
     *
     * @return an {@link Occurrence} carrying this anchor's line hints and raw text.
     */
    public Occurrence toOccurrence() {
        return new Occurrence(docLine, citedLine, rawText);
    }
}
