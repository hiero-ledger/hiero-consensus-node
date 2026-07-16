// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.util.Map;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Lane;

/**
 * What one run scanned and checked — the audit trail that makes silence meaningful: "no findings" reads
 * as checked-and-clean, not never-scanned. All counts are pure functions of the checkout, so the stats
 * are as deterministic as the findings.
 *
 * @param entriesByType        scanned KB entries per document type.
 * @param anchorsByKind        extracted anchors per kind (every citation seen, checked or clean).
 * @param checkGroups          distinct {@code (entry, target, kind)} checks resolved by the per-anchor
 *                             pipeline (Tier-2 diff assemblers are counted separately below).
 * @param findingsByLane       all emitted findings per lane (per-anchor and diff-assembler findings).
 * @param interfaceDocsOptedIn interface docs carrying {@code interface:}/{@code methods:} frontmatter,
 *                             i.e. covered by the Tier-2 method-set diff.
 * @param tunableSections      tunables-catalog sections parsed for the config-record checks.
 * @param tunableRows          tunables-catalog rows parsed for the key/default checks.
 */
public record ScanStats(
        Map<EntryType, Integer> entriesByType,
        Map<AnchorKind, Integer> anchorsByKind,
        int checkGroups,
        Map<Lane, Integer> findingsByLane,
        int interfaceDocsOptedIn,
        int tunableSections,
        int tunableRows) {

    /** The total number of scanned entries (sum over all entry types). */
    public int totalEntries() {
        return entriesByType.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The total number of extracted anchors (sum over all anchor kinds). */
    public int totalAnchors() {
        return anchorsByKind.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The total number of emitted findings (sum over all lanes). */
    public int totalFindings() {
        return findingsByLane.values().stream().mapToInt(Integer::intValue).sum();
    }
}
