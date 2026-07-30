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
 *                    Cited line numbers are never part of the target.
 * @param citedModule the module the citation scopes to, or {@code null} if none is cited. Used to
 *                    distinguish "gone" from "moved to another module".
 * @param citedScope  the enclosing scope for a member (e.g. the class owning a cited method), or
 *                    {@code null}.
 * @param docLine     the 1-based line in the KB file where this citation appears (for navigation).
 * @param citedLine   the cited code line number, or {@link #NO_LINE} if none. A navigation hint only
 *                    — never asserted on; drives auto-fix proposals when the symbol moved.
 * @param rawText     the verbatim citation text, for evidence and diagnostics.
 * @param statedModule the module asserted in prose next to a source citation (e.g. a
 *                    {@code Module: `swirlds-common`} label), or {@code null} when none is stated.
 *                    Cross-checked against the module the linked path actually resolves in.
 * @param historical  whether the citing document's {@code historical:} frontmatter marks this source
 *                    as expected-gone (deliberately deleted code cited as history). Inverts the check:
 *                    absent is expected (quiet), present is drift (the doc claims a deletion that
 *                    never happened or was reverted).
 */
public record Anchor(
        AnchorKind kind,
        String target,
        String citedModule,
        String citedScope,
        int docLine,
        int citedLine,
        String rawText,
        String statedModule,
        boolean historical) {

    /** Sentinel {@code citedLine} value meaning no code line was cited. */
    public static final int NO_LINE = -1;

    /**
     * Convenience constructor for anchors that carry no stated-module label (the common case) and are not
     * marked historical; see the canonical constructor for parameter semantics.
     */
    public Anchor(
            final AnchorKind kind,
            final String target,
            final String citedModule,
            final String citedScope,
            final int docLine,
            final int citedLine,
            final String rawText) {
        this(kind, target, citedModule, citedScope, docLine, citedLine, rawText, null, false);
    }

    /**
     * Convenience constructor for anchors that carry a stated-module label but are not marked historical;
     * see the canonical constructor for parameter semantics.
     */
    public Anchor(
            final AnchorKind kind,
            final String target,
            final String citedModule,
            final String citedScope,
            final int docLine,
            final int citedLine,
            final String rawText,
            final String statedModule) {
        this(kind, target, citedModule, citedScope, docLine, citedLine, rawText, statedModule, false);
    }

    /**
     * A copy of this anchor with {@code historical} set.
     *
     * @return an equivalent anchor marked as an expected-gone (historical) citation.
     */
    public Anchor asHistorical() {
        return new Anchor(kind, target, citedModule, citedScope, docLine, citedLine, rawText, statedModule, true);
    }

    /**
     * The occurrence view of this anchor (drops the resolution-only fields).
     *
     * @return an {@link Occurrence} carrying this anchor's line hints and raw text.
     */
    public Occurrence toOccurrence() {
        return new Occurrence(docLine, citedLine, rawText);
    }
}
