// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

import java.util.List;

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
        Integer autoFixLine) {

    /**
     * Returns the number of places the target is cited in the entry.
     *
     * @return the occurrence count.
     */
    public int occurrenceCount() {
        return occurrences.size();
    }
}
