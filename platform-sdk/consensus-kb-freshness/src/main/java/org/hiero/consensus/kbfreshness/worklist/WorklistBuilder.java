// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.worklist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.extract.AnchorExtractor;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;

/**
 * Builds the semantic worklist: for each architecture topic/interface, whether any anchored source
 * file changed since the topic's {@code last_reviewed} date, decided purely from committed git
 * history. The result scopes the Tier-3 semantic pass so it re-reads only topics whose code moved.
 */
public final class WorklistBuilder {

    /** Matches an ISO {@code yyyy-MM-dd} date used as a {@code last_reviewed} marker. */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The absolute, normalized repository root. */
    private final Path repoRoot;

    /** Extracts anchors from a KB document. */
    private final AnchorExtractor extractor;

    /** Queries committed git history for source file commit dates. */
    private final Git git;

    /** Resolves abbreviated {@code module/.../File.java} citations to concrete indexed paths. */
    private final SourceIndex index;

    /**
     * Creates a builder from its collaborators.
     *
     * @param repoRoot  the repository root (resolved to an absolute, normalized path).
     * @param extractor the anchor extractor.
     * @param git       the git history accessor.
     * @param index     the source index used to resolve abbreviated source citations.
     */
    public WorklistBuilder(
            final Path repoRoot, final AnchorExtractor extractor, final Git git, final SourceIndex index) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.extractor = extractor;
        this.git = git;
        this.index = index;
    }

    /**
     * Builds the semantic worklist for the architecture topics and interfaces among the documents, sorted
     * by entry key.
     *
     * @param docs the KB documents to evaluate.
     * @return one worklist entry per architecture topic/interface.
     */
    public List<WorklistEntry> build(final List<KbDocument> docs) {
        final List<WorklistEntry> entries = new ArrayList<>();
        for (final KbDocument doc : docs) {
            final EntryType type = doc.entry().type();
            if (type != EntryType.ARCHITECTURE_TOPIC && type != EntryType.ARCHITECTURE_INTERFACE) {
                continue;
            }
            entries.add(evaluate(doc));
        }
        entries.sort(Comparator.comparing(WorklistEntry::entryKey));
        return entries;
    }

    /**
     * Evaluates one topic's freshness: {@code REVIEW} when the marker is missing/non-date or anchored
     * source changed since it, {@code FRESH} when nothing changed, {@code UNKNOWN} — with a note naming
     * the reason — when the topic anchors no sources, git is unavailable, or no commit date could be
     * determined. The doc-intrinsic no-sources reason is checked before git availability so it reports
     * the same way in every environment.
     *
     * @param doc the KB document to evaluate.
     * @return the topic's worklist entry.
     */
    private WorklistEntry evaluate(final KbDocument doc) {
        final String key = doc.entry().key();
        final String path = doc.entry().relativePath();
        final String lastReviewed = doc.entry().lastReviewed();

        final List<String> sourcePaths = anchoredSourcePaths(doc);
        final int anchorCount = sourcePaths.size();
        if (lastReviewed == null || !ISO_DATE.matcher(lastReviewed.strip()).matches()) {
            // No usable freshness marker — always route to review.
            return new WorklistEntry(
                    key, path, lastReviewed, WorklistEntry.Status.REVIEW, null, List.of(), anchorCount);
        }
        if (sourcePaths.isEmpty()) {
            return unknown(key, path, lastReviewed, "no anchored sources", anchorCount);
        }
        if (!git.available()) {
            return unknown(key, path, lastReviewed, "git unavailable", anchorCount);
        }

        final String reviewedDate = lastReviewed.strip();
        final List<String> changed = new ArrayList<>();
        boolean anyDateKnown = false;
        for (final String src : sourcePaths) {
            final String commitDate = git.lastCommitDate(src);
            if (commitDate != null) {
                anyDateKnown = true;
                if (commitDate.compareTo(reviewedDate) > 0) {
                    changed.add(src);
                }
            }
        }
        if (!anyDateKnown) {
            return unknown(key, path, lastReviewed, "no commit dates for anchored sources", anchorCount);
        }
        changed.sort(Comparator.naturalOrder());
        final WorklistEntry.Status status =
                changed.isEmpty() ? WorklistEntry.Status.FRESH : WorklistEntry.Status.REVIEW;
        return new WorklistEntry(key, path, lastReviewed, status, null, changed, anchorCount);
    }

    /**
     * Builds an {@link WorklistEntry.Status#UNKNOWN} entry carrying the reason freshness could not be
     * determined.
     *
     * @param key          the topic entry key.
     * @param path         the topic's repo-relative path.
     * @param lastReviewed the topic's {@code last_reviewed} value.
     * @param note         the reason freshness is unknown.
     * @param anchorCount  how many source files the topic anchors.
     * @return the unknown-status entry.
     */
    private static WorklistEntry unknown(
            final String key, final String path, final String lastReviewed, final String note, final int anchorCount) {
        return new WorklistEntry(key, path, lastReviewed, WorklistEntry.Status.UNKNOWN, note, List.of(), anchorCount);
    }

    /**
     * Distinct concrete repo-relative source files anchored by the topic. Full-path citations are used
     * as-is when they exist; abbreviated {@code module/.../File.java} citations — the KB's mandated inline
     * style — are resolved through the source index so freshness is measured against the same files the
     * resolver sees, not silently dropped.
     *
     * @param doc the KB document whose anchors are scanned.
     * @return the sorted, distinct anchored source paths.
     */
    private List<String> anchoredSourcePaths(final KbDocument doc) {
        final TreeSet<String> paths = new TreeSet<>();
        for (final Anchor a : extractor.extract(doc)) {
            if (a.kind() == AnchorKind.SOURCE_PATH) {
                paths.addAll(resolveAnchoredSources(a));
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * Resolves one {@link AnchorKind#SOURCE_PATH} anchor to the concrete repo-relative files it names. A
     * full path resolves to itself when it exists on disk. An abbreviated {@code module/.../File.java}
     * citation resolves through the source index by basename within the cited module (mirroring
     * {@code AnchorResolver}); with no cited module, every indexed file of that basename is taken. A
     * citation whose cited location is stale but whose basename resolves at exactly one other indexed
     * path — the resolver's package/path-move signal — is tracked at that new location: the topics whose
     * code moved wholesale are exactly the ones whose prose most needs the semantic pass, so a moved
     * anchor must keep feeding the freshness comparison rather than silently dropping out. Only a
     * citation that resolves nowhere (or ambiguously) contributes no source — a gone anchor is reported
     * as drift elsewhere.
     *
     * @param a a source-path anchor.
     * @return the concrete repo-relative source paths the anchor names (possibly empty).
     */
    private List<String> resolveAnchoredSources(final Anchor a) {
        final String target = a.target();
        final String basename = target.substring(target.lastIndexOf('/') + 1);
        if (!target.contains("/.../")) {
            if (Files.isRegularFile(repoRoot.resolve(target))) {
                return List.of(target);
            }
            return uniqueMove(basename);
        }
        final List<String> resolved = new ArrayList<>();
        for (final String p : index.pathsForBasename(basename)) {
            if (a.citedModule() == null || a.citedModule().equals(moduleOf(p))) {
                resolved.add(p);
            }
        }
        return resolved.isEmpty() ? uniqueMove(basename) : resolved;
    }

    /**
     * The single indexed path of a basename whose cited location is stale, or nothing when the basename
     * is gone or ambiguous (mirroring the resolver, which only reports a resolved path for a unique
     * package/path move).
     *
     * @param basename the cited file basename.
     * @return the unique moved-to path, or an empty list.
     */
    private List<String> uniqueMove(final String basename) {
        final List<String> candidates = index.pathsForBasename(basename);
        return candidates.size() == 1 ? List.of(candidates.get(0)) : List.of();
    }

    /**
     * The module directory of a repo-relative path (the segment preceding {@code src}).
     *
     * @param repoRelPath the repo-relative path.
     * @return the module name, or {@code null} if the path has no {@code src} segment.
     */
    private static String moduleOf(final String repoRelPath) {
        final String[] parts = repoRelPath.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("src")) {
                return parts[i - 1];
            }
        }
        return null;
    }
}
