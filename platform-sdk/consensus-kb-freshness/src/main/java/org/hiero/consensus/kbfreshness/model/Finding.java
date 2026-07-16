// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

import java.util.List;
import org.hiero.consensus.kbfreshness.util.Hashing;

/**
 * A collapsed, deterministic finding: one per {@code (entry, target, check)}. All fields are pure
 * functions of the checkout, so the machine artifact is byte-identical across runs. Human-owned
 * triage and dates live in the baseline, not here, to keep this artifact reproducible.
 *
 * @param id          stable 16-hex-char identity = hash of {@code (entryKey, target, kind)}. No line
 *                    numbers, no file path — survives file moves and code renames until the KB text
 *                    itself changes.
 * @param entryKey    the citing entry's stable key (catalog ID or slug).
 * @param entryPath   the citing entry's repo-relative path (display/navigation only).
 * @param entryType   the citing entry's document class.
 * @param kind        the anchor kind / check applied.
 * @param target      the exact subject checked.
 * @param citedModule the module the citation scoped to, or {@code null}.
 * @param citedScope  the enclosing scope for a member, or {@code null}.
 * @param outcome     the three-valued result.
 * @param lane        where this finding is routed.
 * @param question    the exact question the resolver asked.
 * @param evidence    a one-look, curator-verifiable justification.
 * @param occurrences every place the target is cited in the entry, sorted; carries the line hints.
 * @param autoFixLine for an {@link Lane#AUTO_FIX} finding, the corrected code line; otherwise {@code null}.
 * @param resolvedPath for a package/path move with exactly one candidate, the repo-relative path the
 *                    cited source actually resolves at (drives a path-rewrite auto-fix proposal);
 *                    otherwise {@code null}.
 * @param statedModule the module asserted in prose next to the citation (e.g. a {@code Module: `X`}
 *                    label), or {@code null} when none is stated. Kept off the machine artifact; used
 *                    only to complete a path-move auto-fix by rewriting a stale on-line module label.
 */
public record Finding(
        String id,
        String entryKey,
        String entryPath,
        EntryType entryType,
        AnchorKind kind,
        String target,
        String citedModule,
        String citedScope,
        Outcome outcome,
        Lane lane,
        String question,
        String evidence,
        List<Occurrence> occurrences,
        Integer autoFixLine,
        String resolvedPath,
        String statedModule) {

    /**
     * Creates a finding, deriving the stable {@link #id} from {@code (entry.key(), target, kind)} so the
     * identity can never desync from the fields it is hashed over. The three move-completion fields
     * ({@link #autoFixLine}, {@link #resolvedPath}, {@link #statedModule}) default to {@code null}; set
     * the ones a finding needs with {@link #withAutoFixLine}, {@link #withResolvedPath},
     * {@link #withStatedModule}.
     *
     * @param entry       the citing entry (supplies key, path, and type).
     * @param kind        the anchor kind / check applied.
     * @param target      the exact subject checked.
     * @param citedModule the module the citation scoped to, or {@code null}.
     * @param citedScope  the enclosing scope for a member, or {@code null}.
     * @param outcome     the three-valued result.
     * @param lane        where this finding is routed.
     * @param question    the exact question the resolver asked.
     * @param evidence    a one-look, curator-verifiable justification.
     * @param occurrences every place the target is cited in the entry, sorted.
     * @return the assembled finding with a computed identity hash.
     */
    public static Finding of(
            final Entry entry,
            final AnchorKind kind,
            final String target,
            final String citedModule,
            final String citedScope,
            final Outcome outcome,
            final Lane lane,
            final String question,
            final String evidence,
            final List<Occurrence> occurrences) {
        return new Finding(
                Hashing.id(entry.key(), target, kind.name()),
                entry.key(),
                entry.relativePath(),
                entry.type(),
                kind,
                target,
                citedModule,
                citedScope,
                outcome,
                lane,
                question,
                evidence,
                occurrences,
                null,
                null,
                null);
    }

    /** Returns a copy of this finding with the corrected auto-fix line set. */
    public Finding withAutoFixLine(final Integer newAutoFixLine) {
        return new Finding(
                id,
                entryKey,
                entryPath,
                entryType,
                kind,
                target,
                citedModule,
                citedScope,
                outcome,
                lane,
                question,
                evidence,
                occurrences,
                newAutoFixLine,
                resolvedPath,
                statedModule);
    }

    /** Returns a copy of this finding with the resolved package/path-move location set. */
    public Finding withResolvedPath(final String newResolvedPath) {
        return new Finding(
                id,
                entryKey,
                entryPath,
                entryType,
                kind,
                target,
                citedModule,
                citedScope,
                outcome,
                lane,
                question,
                evidence,
                occurrences,
                autoFixLine,
                newResolvedPath,
                statedModule);
    }

    /** Returns a copy of this finding with the prose-stated module label set. */
    public Finding withStatedModule(final String newStatedModule) {
        return new Finding(
                id,
                entryKey,
                entryPath,
                entryType,
                kind,
                target,
                citedModule,
                citedScope,
                outcome,
                lane,
                question,
                evidence,
                occurrences,
                autoFixLine,
                resolvedPath,
                newStatedModule);
    }

    /**
     * Returns the number of places the target is cited in the entry.
     *
     * @return the occurrence count.
     */
    public int occurrenceCount() {
        return occurrences.size();
    }
}
