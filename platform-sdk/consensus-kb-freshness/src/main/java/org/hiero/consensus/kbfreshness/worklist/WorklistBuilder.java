// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.worklist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.hiero.consensus.kbfreshness.extract.AnchorExtractor;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.resolve.SourceCandidates;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.Patterns;

/**
 * Builds the semantic worklist: for each architecture topic/interface, whether any anchored source
 * file changed since the topic's {@code last_reviewed} date, decided purely from committed git
 * history. The result scopes the Tier-3 semantic pass so it re-reads only topics whose code moved.
 */
public final class WorklistBuilder {

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
        if (lastReviewed == null
                || !Patterns.ISO_DATE.matcher(lastReviewed.strip()).matches()) {
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
     * Distinct concrete repo-relative source files anchored by the topic: full-path citations as-is,
     * abbreviated {@code module/.../File.java} citations resolved through the source index, and
     * fully-qualified type citations resolved by package + simple name.
     *
     * @param doc the KB document whose anchors are scanned.
     * @return the sorted, distinct anchored source paths.
     */
    private List<String> anchoredSourcePaths(final KbDocument doc) {
        final TreeSet<String> paths = new TreeSet<>();
        for (final Anchor a : extractor.extract(doc)) {
            if (a.kind() == AnchorKind.SOURCE_PATH) {
                paths.addAll(resolveAnchoredSources(a));
            } else if (a.kind() == AnchorKind.CLASS) {
                paths.addAll(resolveFqnSources(a));
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * Resolves one {@link AnchorKind#CLASS} (fully-qualified type) anchor to the concrete source files it
     * names: the indexed files matching the cited package, or the unique indexed path when the package moved.
     *
     * @param a a fully-qualified type anchor; its cited scope is the primary type name.
     * @return the concrete repo-relative source paths the anchor names (possibly empty).
     */
    private List<String> resolveFqnSources(final Anchor a) {
        final List<String> resolved = SourceCandidates.forFqn(index, a.target(), a.citedScope());
        return resolved.isEmpty() ? SourceCandidates.uniqueMove(index, a.citedScope() + ".java") : resolved;
    }

    /**
     * Resolves one {@link AnchorKind#SOURCE_PATH} anchor to the concrete repo-relative files it names: a
     * full path resolves to itself, an abbreviated {@code module/.../File.java} citation resolves through
     * the source index by basename within the cited module (mirroring {@code AnchorResolver}), and a
     * citation whose location moved is tracked at the unique new path. A citation that resolves nowhere or
     * ambiguously contributes no source — a gone anchor is reported as drift elsewhere.
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
            return SourceCandidates.uniqueMove(index, basename);
        }
        final List<String> resolved = SourceCandidates.inModule(index, basename, a.citedModule());
        return resolved.isEmpty() ? SourceCandidates.uniqueMove(index, basename) : resolved;
    }
}
