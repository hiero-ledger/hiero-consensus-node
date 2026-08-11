// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.engine.ScanStats;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.model.Triage;
import org.hiero.consensus.kbfreshness.resolve.ConfigRecords;
import org.hiero.consensus.kbfreshness.util.Markdown;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * Renders the human-readable drift report — the assertions a curator acts on. Only {@code assert}-lane
 * findings that are not {@code dismissed} appear here; unverifiable results live in the quiet log and
 * line-only drift in the auto-fix proposals. New drift (ids absent from the baseline) is surfaced first
 * as the signal.
 */
public final class ReportRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private ReportRenderer() {}

    /**
     * Renders the human-readable drift report as Markdown.
     *
     * @param result  the run result.
     * @param runDate the run date shown in the report header; blank or {@code null} omits it.
     * @return the rendered Markdown report.
     */
    public static String render(final RunResult result, final String runDate) {
        final List<BaselineJoin.Joined> asserts = new ArrayList<>();
        for (final BaselineJoin.Joined j : result.join().joined()) {
            if (j.finding().lane() == Lane.ASSERT && j.triage() != Triage.DISMISSED) {
                asserts.add(j);
            }
        }
        final List<BaselineJoin.Joined> newDrift =
                asserts.stream().filter(BaselineJoin.Joined::isNew).toList();
        final List<BaselineJoin.Joined> carried =
                asserts.stream().filter(j -> !j.isNew()).toList();

        final long dismissed = result.join().joined().stream()
                .filter(j -> j.finding().lane() == Lane.ASSERT && j.triage() == Triage.DISMISSED)
                .count();
        final long quiet = countLane(result, Lane.QUIET_LOG);
        final long lineMoves = result.findings().stream()
                .filter(f -> f.lane() == Lane.AUTO_FIX && f.autoFixLine() != null)
                .count();
        final long symbolMigrations = result.findings().stream()
                .filter(f -> f.lane() == Lane.AUTO_FIX && f.autoFixSymbol() != null)
                .count();
        // Path-move asserts still assert (the KB claim is wrong until edited) but each carries a ready
        // path-rewrite, so they are mechanically fixable alongside the line moves and symbol migrations.
        final long pathMoves =
                asserts.stream().filter(j -> j.finding().resolvedPath() != null).count();

        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness report\n\n");
        if (runDate != null && !runDate.isBlank()) {
            sb.append("Run date: ").append(runDate).append("\n\n");
        }
        sb.append("## Summary\n\n");
        sb.append("| Category | Count |\n|---|---|\n");
        sb.append("| New drift (assert) | ").append(newDrift.size()).append(" |\n");
        sb.append("| Carried drift (assert) | ").append(carried.size()).append(" |\n");
        sb.append("| Dismissed (suppressed) | ").append(dismissed).append(" |\n");
        sb.append("| Resolved since last run | ")
                .append(result.join().resolvedIds().size())
                .append(" |\n");
        sb.append("| Unverifiable (quiet log) | ").append(quiet).append(" |\n");
        sb.append("| Auto-fix — moved lines | ").append(lineMoves).append(" |\n");
        sb.append("| Auto-fix — `:NN`→`#symbol` migrations | ")
                .append(symbolMigrations)
                .append(" |\n");
        sb.append("| Auto-fix — path moves (assert + ready rewrite) | ")
                .append(pathMoves)
                .append(" |\n");
        sb.append("| Fixable now with `--fix` | ")
                .append(lineMoves + symbolMigrations + pathMoves)
                .append(" |\n");
        // The Tier-3 semantic pass runs outside this engine (the skill); surfacing its pending workload
        // here keeps a standalone engine run from reading as "everything was checked".
        final long review = countWorklist(result, WorklistEntry.Status.REVIEW);
        final long unknown = countWorklist(result, WorklistEntry.Status.UNKNOWN);
        sb.append("| Semantic worklist pending (run by the skill, not this engine) | ")
                .append(review)
                .append(" review / ")
                .append(unknown)
                .append(" unknown |\n\n");

        scanCoverage(sb, result);
        rollup(sb, asserts, result);

        section(sb, "New drift", newDrift, "New assertions since the baseline — the primary signal.");
        section(sb, "Carried drift", carried, "Previously-seen assertions still present.");

        sb.append("## Resolved since last run\n\n");
        if (result.join().resolvedIds().isEmpty()) {
            sb.append("_None._\n\n");
        } else {
            for (final String id : result.join().resolvedIds()) {
                sb.append("- `").append(id).append("` — auto-closed (no longer present).\n");
            }
            sb.append('\n');
        }

        sb.append("---\n\n");
        sb.append("Unverifiable results are in `quiet-log.md`; ready line-and-path fixes are in ")
                .append("`auto-fix.md` (apply with `--fix`); did-you-mean hints for gone targets are in ")
                .append("`suggestions.md`; the semantic worklist is in `worklist.md`. ")
                .append("The machine artifact is `findings.json`.\n");
        return sb.toString();
    }

    /**
     * Appends the scan-coverage section: what was scanned and checked, so silence elsewhere reads as
     * "checked and clean" rather than "never looked at".
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void scanCoverage(final StringBuilder sb, final RunResult result) {
        final ScanStats s = result.stats();
        sb.append("## Scan coverage\n\n");
        sb.append("_What this run scanned and checked — silence elsewhere means checked-and-clean, "
                + "not not-looked-at._\n\n");
        sb.append("- Entries scanned: ")
                .append(s.totalEntries())
                .append(" — ")
                .append(countList(s.entriesByType()))
                .append('\n');
        sb.append("- Anchors extracted: ")
                .append(s.totalAnchors())
                .append(" — ")
                .append(countList(s.anchorsByKind()))
                .append('\n');
        final long anchorFindings = result.findings().stream()
                .filter(f -> f.kind() != AnchorKind.INTERFACE_METHOD
                        && f.kind() != AnchorKind.CONFIG_KEY
                        && f.kind() != AnchorKind.CONFIG_PREFIX
                        && f.kind() != AnchorKind.CONFIG_DEFAULT)
                .count();
        sb.append("- Distinct anchor checks (one per entry × target × check kind): ")
                .append(s.checkGroups())
                .append(" — ")
                .append(anchorFindings)
                .append(" produced a finding, ")
                .append(s.checkGroups() - anchorFindings)
                .append(" resolved clean. A target cited by N entries counts as N checks; the Tier-2 "
                        + "catalog/interface diff checks are separate and appear only in the lane counts below.\n");
        sb.append("- Findings by lane: ").append(countList(s.findingsByLane())).append('\n');
        sb.append("- Tier-2 diff surfaces: ")
                .append(s.interfaceDocsOptedIn())
                .append(" interface doc(s) opted in; tunables catalog ")
                .append(s.tunableSections())
                .append(" section(s) / ")
                .append(s.tunableRows())
                .append(" row(s)\n\n");
    }

    /**
     * Formats an enum-keyed count map as {@code name 3, other-name 1}, in the enums' declaration order,
     * with zero-count keys omitted.
     *
     * @param counts the counts keyed by enum constant.
     * @return the formatted list, or {@code none} when empty.
     */
    private static String countList(final Map<? extends Enum<?>, Integer> counts) {
        final List<String> parts = new ArrayList<>();
        for (final Map.Entry<? extends Enum<?>, Integer> e : counts.entrySet()) {
            parts.add(Markdown.humanize(e.getKey()) + " " + e.getValue());
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    /**
     * The grouping key of a path-move rollup row: the old cited path and the single new path it resolves
     * to. Ordered by old path then new path so the rendered rows are stable.
     *
     * @param from the old cited path.
     * @param to   the resolved new path.
     */
    private record MoveKey(String from, String to) implements Comparable<MoveKey> {

        @Override
        public int compareTo(final MoveKey o) {
            final int byFrom = from.compareTo(o.from);
            return byFrom != 0 ? byFrom : to.compareTo(o.to);
        }
    }

    /**
     * Appends the root-cause rollup: path moves grouped by their old-to-new rewrite (one underlying code
     * move often stales many citations), gone targets cited by more than one entry, and gone config keys
     * grouped by the record that now declares a same-named key (a key-extraction refactor reads as one
     * cause, not N key findings). Rendered only when at least one group exists, so the section never
     * appears as empty noise.
     *
     * @param sb      the buffer to append to.
     * @param asserts the non-dismissed assert-lane findings (new and carried).
     * @param result  the run result, for the config-record scan behind the key-migration grouping.
     */
    private static void rollup(
            final StringBuilder sb, final List<BaselineJoin.Joined> asserts, final RunResult result) {
        final Map<MoveKey, List<Finding>> moves = new TreeMap<>();
        final Map<String, List<Finding>> gone = new TreeMap<>();
        for (final BaselineJoin.Joined j : asserts) {
            final Finding f = j.finding();
            if (f.resolvedPath() != null) {
                moves.computeIfAbsent(new MoveKey(f.target(), f.resolvedPath()), k -> new ArrayList<>())
                        .add(f);
            } else if (f.outcome() == Outcome.ABSENT) {
                gone.computeIfAbsent(f.target(), k -> new ArrayList<>()).add(f);
            }
        }
        gone.values().removeIf(fs -> fs.size() < 2);
        final Map<String, List<String>> migrations = keyMigrations(asserts, result);
        if (moves.isEmpty() && gone.isEmpty() && migrations.isEmpty()) {
            return;
        }
        sb.append("## Root causes (rollup)\n\n");
        sb.append("_The same underlying change grouped across entries — read this before the per-entry "
                + "findings below._\n\n");
        if (!moves.isEmpty()) {
            sb.append("### Path moves\n\n");
            for (final Map.Entry<MoveKey, List<Finding>> e : moves.entrySet()) {
                sb.append("- `")
                        .append(e.getKey().from())
                        .append("` → `")
                        .append(e.getKey().to())
                        .append("` — ")
                        .append(distinctEntries(e.getValue()).size())
                        .append(" doc(s), ")
                        .append(e.getValue().stream()
                                .mapToInt(Finding::occurrenceCount)
                                .sum())
                        .append(" citation(s)\n");
            }
            sb.append('\n');
        }
        if (!gone.isEmpty()) {
            sb.append("### Gone targets cited by multiple entries\n\n");
            for (final Map.Entry<String, List<Finding>> e : gone.entrySet()) {
                final List<String> entries = distinctEntries(e.getValue());
                sb.append("- `")
                        .append(e.getKey())
                        .append("` — cited by ")
                        .append(entries.size())
                        .append(": ")
                        .append(String.join(", ", entries))
                        .append('\n');
            }
            sb.append('\n');
        }
        if (!migrations.isEmpty()) {
            sb.append("### Config-key migrations\n\n");
            sb.append("_Gone keys a single other record declares same-named — directional hints (see "
                    + "`suggestions.md`), not asserted facts._\n\n");
            for (final Map.Entry<String, List<String>> e : migrations.entrySet()) {
                sb.append("- keys now declared by ")
                        .append(e.getKey())
                        .append(": ")
                        .append(String.join(", ", e.getValue()))
                        .append('\n');
            }
            sb.append('\n');
        }
    }

    /**
     * Groups the gone config-key assertions by the record that now declares a component of the same
     * name, when exactly one indexed record does — the same exact-match rule behind the key-migration
     * hints in {@code suggestions.md}. Keys with no or several same-named owners are left out; they are
     * not a groupable cause.
     *
     * @param asserts the non-dismissed assert-lane findings.
     * @param result  the run result, for the config-record scan.
     * @return {@code className (path)} of the migration target to its {@code `old` → `new`} key
     *     rewrites, in stable order.
     */
    private static Map<String, List<String>> keyMigrations(
            final List<BaselineJoin.Joined> asserts, final RunResult result) {
        List<ConfigRecords.Owner> owners = null;
        final Map<String, List<String>> migrations = new TreeMap<>();
        for (final BaselineJoin.Joined j : asserts) {
            final Finding f = j.finding();
            if (f.kind() != AnchorKind.CONFIG_KEY || f.outcome() != Outcome.ABSENT) {
                continue;
            }
            if (owners == null) {
                owners = ConfigRecords.scan(result.sourceIndex());
            }
            // A groupable cause only when exactly one record now declares the same-named key.
            final List<ConfigRecords.Owner> declaring = ConfigRecords.declaringRecordsOf(owners, f.target());
            if (declaring.size() != 1) {
                continue;
            }
            final ConfigRecords.Owner match = declaring.get(0);
            final int lastDot = f.target().lastIndexOf('.');
            final String goneProp = lastDot >= 0 ? f.target().substring(lastDot + 1) : f.target();
            final String newKey = match.type().fullyQualifiedKey(goneProp);
            migrations
                    .computeIfAbsent("`" + match.className() + "` (`" + match.path() + "`)", k -> new ArrayList<>())
                    .add("`" + f.target() + "` → `" + newKey + "`");
        }
        return migrations;
    }

    /**
     * The sorted, distinct entry keys of a finding group.
     *
     * @param findings the grouped findings.
     * @return the distinct entry keys.
     */
    private static List<String> distinctEntries(final List<Finding> findings) {
        return findings.stream().map(Finding::entryKey).distinct().sorted().toList();
    }

    /**
     * Appends one report section, grouping items by entry key.
     *
     * @param sb    the buffer to append to.
     * @param title the section heading.
     * @param items the findings to render in the section.
     * @param blurb the italic description under the heading.
     */
    private static void section(
            final StringBuilder sb, final String title, final List<BaselineJoin.Joined> items, final String blurb) {
        sb.append("## ").append(title).append("\n\n");
        sb.append('_').append(blurb).append("_\n\n");
        if (items.isEmpty()) {
            sb.append("_None._\n\n");
            return;
        }
        String currentEntry = null;
        for (final BaselineJoin.Joined j : items) {
            final Finding f = j.finding();
            if (!f.entryKey().equals(currentEntry)) {
                currentEntry = f.entryKey();
                sb.append("### ").append(currentEntry).append("  \n");
                sb.append("`").append(f.entryPath()).append("`\n\n");
            }
            sb.append("- **")
                    .append(label(f))
                    .append("** (")
                    .append(Markdown.humanize(f.kind()))
                    .append(") `")
                    .append(f.target())
                    .append("` — id `")
                    .append(f.id())
                    .append("`\n");
            sb.append("  - ").append(f.evidence()).append('\n');
            sb.append("  - occurrences (").append(f.occurrenceCount()).append("): ");
            final List<String> occ = new ArrayList<>();
            for (final Occurrence o : f.occurrences()) {
                final String cited = o.citedLine() < 0 ? "" : ":" + o.citedLine();
                occ.add("line " + o.docLine() + cited);
            }
            sb.append(String.join("; ", occ)).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Returns the display label for a finding: {@code GONE} when absent, otherwise {@code MOVED}.
     *
     * @param f the finding.
     * @return the label.
     */
    private static String label(final Finding f) {
        return f.outcome() == Outcome.ABSENT ? "GONE" : "MOVED";
    }

    /**
     * Counts the findings assigned to a given lane.
     *
     * @param result the run result.
     * @param lane   the lane to count.
     * @return the number of findings in that lane.
     */
    private static long countLane(final RunResult result, final Lane lane) {
        return result.findings().stream().filter(f -> f.lane() == lane).count();
    }

    /**
     * Counts the semantic-worklist entries with a given status.
     *
     * @param result the run result.
     * @param status the worklist status to count.
     * @return the number of worklist entries with that status.
     */
    private static long countWorklist(final RunResult result, final WorklistEntry.Status status) {
        return result.worklist().stream().filter(e -> e.status() == status).count();
    }
}
