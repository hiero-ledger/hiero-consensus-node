// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.kbfreshness.extract.AnchorExtractor;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.extract.KbScanner;
import org.hiero.consensus.kbfreshness.extract.TunablesCatalog;
import org.hiero.consensus.kbfreshness.findings.Baseline;
import org.hiero.consensus.kbfreshness.findings.BaselineJoin;
import org.hiero.consensus.kbfreshness.findings.FindingAssembler;
import org.hiero.consensus.kbfreshness.findings.InterfaceDiffAssembler;
import org.hiero.consensus.kbfreshness.findings.TunablesDiffAssembler;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.AnchorResolver;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.worklist.WorklistBuilder;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * Orchestrates one deterministic run: scan the KB, index sources, extract and resolve anchors,
 * collapse findings, join the baseline, and build the semantic worklist. No model, no network.
 */
public final class Engine {

    /** The inputs for this run. */
    private final RunConfig config;

    /**
     * Creates an engine for the given run configuration.
     *
     * @param config the run inputs.
     */
    public Engine(final RunConfig config) {
        this.config = config;
    }

    /**
     * Executes the run and returns its full deterministic output.
     *
     * @return the scanned documents, collapsed findings, baseline join, semantic worklist, and scan stats.
     */
    public RunResult run() {
        final KbScanner scanner = new KbScanner(config.repoRoot(), config.kbRoot());
        final List<KbDocument> docs = scanner.scan();

        final SourceIndex index = SourceIndex.build(config.repoRoot(), config.moduleRoots());
        final AnchorExtractor extractor = new AnchorExtractor(config.repoRoot(), config.kbRoot());
        final AnchorResolver resolver =
                new AnchorResolver(config.repoRoot(), config.kbRoot(), index, config.allowlist());
        final FindingAssembler assembler = new FindingAssembler(extractor, resolver);
        final List<Finding> findings = new ArrayList<>(assembler.assembleAll(docs));
        findings.addAll(new InterfaceDiffAssembler(index).assembleAll(docs));
        findings.addAll(new TunablesDiffAssembler(index).assembleAll(docs));
        subsumeConfigClassMoves(findings);
        findings.sort(FindingAssembler.ORDER);

        final Baseline baseline = Baseline.load(config.baselineFile());
        final BaselineJoin.Result join = BaselineJoin.join(findings, baseline, config.runDate());

        final Git git = new Git(config.repoRoot());
        final List<WorklistEntry> worklist = new WorklistBuilder(config.repoRoot(), extractor, git, index).build(docs);

        return new RunResult(
                docs,
                findings,
                join,
                worklist,
                index,
                stats(docs, extractor, findings),
                git,
                lineSuggestions(docs, extractor, index, config.repoRoot()));
    }

    /**
     * Collects the advisory line suggestions: every {@code File.java:NN} source reference whose cited line
     * is not a declaration, so {@code --fix} cannot migrate it to a symbol. A line inside a body carries
     * its enclosing declaration; a line past end-of-file carries none. Deterministic (parsed from the
     * current checkout), ordered by document then citation.
     *
     * @param docs      the scanned documents.
     * @param extractor the anchor extractor.
     * @param index     the source index.
     * @param repoRoot  the repository root, for reading file lengths.
     * @return the ordered line suggestions.
     */
    private static List<LineSuggestion> lineSuggestions(
            final List<KbDocument> docs,
            final AnchorExtractor extractor,
            final SourceIndex index,
            final Path repoRoot) {
        final List<LineSuggestion> out = new ArrayList<>();
        final Map<String, Integer> lineCounts = new HashMap<>();
        for (final KbDocument doc : docs) {
            for (final Anchor a : extractor.extract(doc)) {
                final boolean sourceRef = a.kind() == AnchorKind.SOURCE_PATH || a.kind() == AnchorKind.SOURCE_BASENAME;
                if (!sourceRef || a.citedLine() == Anchor.NO_LINE) {
                    continue;
                }
                final String path = resolveCitedFile(a, index);
                if (path == null || JavaParsing.symbolAtLine(index.parse(path), a.citedLine()) != null) {
                    // Unresolvable to a single file, or the line IS a declaration (the migration handles it).
                    continue;
                }
                final int count = lineCounts.computeIfAbsent(path, p -> lineCountOf(repoRoot, p));
                final String enclosing = a.citedLine() > count
                        ? null
                        : JavaParsing.enclosingSymbolAtLine(index.parse(path), a.citedLine());
                out.add(new LineSuggestion(
                        doc.entry().key(),
                        doc.entry().relativePath(),
                        a.docLine(),
                        path.substring(path.lastIndexOf('/') + 1),
                        a.citedLine(),
                        enclosing,
                        count));
            }
        }
        return out;
    }

    /**
     * The single repo-relative file a cited source reference resolves to: the cited path for an existing
     * {@link AnchorKind#SOURCE_PATH}, or the unique indexed path for a {@link AnchorKind#SOURCE_BASENAME};
     * {@code null} when it cannot be pinned to one file.
     *
     * @param a     the source anchor.
     * @param index the source index.
     * @return the resolved repo-relative path, or {@code null}.
     */
    private static String resolveCitedFile(final Anchor a, final SourceIndex index) {
        if (a.kind() == AnchorKind.SOURCE_PATH) {
            return index.fileExists(a.target()) ? a.target() : null;
        }
        final List<String> paths = index.pathsForBasename(a.target());
        return paths.size() == 1 ? paths.get(0) : null;
    }

    /**
     * The line count of a repo-relative file, or {@code 0} when it cannot be read.
     *
     * @param repoRoot    the repository root.
     * @param repoRelPath the repo-relative file path.
     * @return the file's line count, or {@code 0} on an IO error.
     */
    private static int lineCountOf(final Path repoRoot, final String repoRelPath) {
        try {
            return Files.readAllLines(repoRoot.resolve(repoRelPath)).size();
        } catch (final IOException e) {
            return 0;
        }
    }

    /**
     * Drops a source-path GONE finding whose citation is already asserted as a config-class move by a
     * {@code CONFIG_PREFIX} finding for the same entry and cited path. Both describe the same line — the
     * section's {@code Source:} link — and the prefix finding is the stronger one (it names the successor
     * record and carries the ready rewrite), so keeping both would double-report one root cause.
     *
     * @param findings the assembled findings, filtered in place.
     */
    private static void subsumeConfigClassMoves(final List<Finding> findings) {
        final Set<String> prefixMoves = new HashSet<>();
        for (final Finding f : findings) {
            if (f.kind() == AnchorKind.CONFIG_PREFIX && f.lane() == Lane.ASSERT && f.resolvedPath() != null) {
                prefixMoves.add(f.entryKey() + "|" + f.target());
            }
        }
        if (prefixMoves.isEmpty()) {
            return;
        }
        findings.removeIf(f -> f.kind() == AnchorKind.SOURCE_PATH
                && f.outcome() == Outcome.ABSENT
                && prefixMoves.contains(f.entryKey() + "|" + f.target()));
    }

    /**
     * Collects the run's scan-coverage statistics: entries by type, extracted anchors by kind, distinct
     * per-anchor checks, findings by lane, and the Tier-2 diff surfaces (interface opt-ins, tunables
     * sections/rows). Extraction is repeated here — it is cheap relative to resolution, and keeping the
     * counting out of the assembler keeps both single-purpose.
     *
     * @param docs      the scanned documents.
     * @param extractor the anchor extractor.
     * @param findings  the final, collapsed findings.
     * @return the run's scan stats.
     */
    private static ScanStats stats(
            final List<KbDocument> docs, final AnchorExtractor extractor, final List<Finding> findings) {
        final Map<EntryType, Integer> entriesByType = new EnumMap<>(EntryType.class);
        final Map<AnchorKind, Integer> anchorsByKind = new EnumMap<>(AnchorKind.class);
        final Set<String> groups = new HashSet<>();
        int interfaceOptIns = 0;
        int tunableSections = 0;
        int tunableRows = 0;
        for (final KbDocument doc : docs) {
            entriesByType.merge(doc.entry().type(), 1, Integer::sum);
            for (final Anchor a : extractor.extract(doc)) {
                anchorsByKind.merge(a.kind(), 1, Integer::sum);
                groups.add(doc.entry().key() + "|" + a.kind().name() + "|" + a.target());
            }
            if (doc.entry().type() == EntryType.ARCHITECTURE_INTERFACE && InterfaceDiffAssembler.optsIntoTier2(doc)) {
                interfaceOptIns++;
            }
            if (doc.entry().type() == EntryType.TUNABLE_CATALOG) {
                for (final TunablesCatalog.Section s : TunablesCatalog.parse(doc)) {
                    tunableSections++;
                    tunableRows += s.rows().size();
                }
            }
        }
        final Map<Lane, Integer> findingsByLane = new EnumMap<>(Lane.class);
        for (final Finding f : findings) {
            findingsByLane.merge(f.lane(), 1, Integer::sum);
        }
        return new ScanStats(
                entriesByType,
                anchorsByKind,
                groups.size(),
                findingsByLane,
                interfaceOptIns,
                tunableSections,
                tunableRows);
    }
}
