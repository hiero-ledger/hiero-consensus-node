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

    /**
     * Creates a builder from its collaborators.
     *
     * @param repoRoot  the repository root (resolved to an absolute, normalized path).
     * @param extractor the anchor extractor.
     * @param git       the git history accessor.
     */
    public WorklistBuilder(final Path repoRoot, final AnchorExtractor extractor, final Git git) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.extractor = extractor;
        this.git = git;
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
     * source changed since it, {@code FRESH} when nothing changed, {@code UNKNOWN} when git is unavailable
     * or no commit date could be determined.
     *
     * @param doc the KB document to evaluate.
     * @return the topic's worklist entry.
     */
    private WorklistEntry evaluate(final KbDocument doc) {
        final String key = doc.entry().key();
        final String path = doc.entry().relativePath();
        final String lastReviewed = doc.entry().lastReviewed();

        final List<String> sourcePaths = anchoredSourcePaths(doc);
        if (lastReviewed == null || !ISO_DATE.matcher(lastReviewed.strip()).matches()) {
            // No usable freshness marker — always route to review.
            return new WorklistEntry(key, path, lastReviewed, WorklistEntry.Status.REVIEW, List.of());
        }
        if (!git.available() || sourcePaths.isEmpty()) {
            return new WorklistEntry(key, path, lastReviewed, WorklistEntry.Status.UNKNOWN, List.of());
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
            return new WorklistEntry(key, path, lastReviewed, WorklistEntry.Status.UNKNOWN, List.of());
        }
        changed.sort(Comparator.naturalOrder());
        final WorklistEntry.Status status =
                changed.isEmpty() ? WorklistEntry.Status.FRESH : WorklistEntry.Status.REVIEW;
        return new WorklistEntry(key, path, lastReviewed, status, changed);
    }

    /**
     * Distinct repo-relative source files anchored by the topic (existing, non-abbreviated).
     *
     * @param doc the KB document whose anchors are scanned.
     * @return the sorted, distinct anchored source paths.
     */
    private List<String> anchoredSourcePaths(final KbDocument doc) {
        final TreeSet<String> paths = new TreeSet<>();
        for (final Anchor a : extractor.extract(doc)) {
            if (a.kind() == AnchorKind.SOURCE_PATH
                    && !a.target().contains("/.../")
                    && Files.isRegularFile(repoRoot.resolve(a.target()))) {
                paths.add(a.target());
            }
        }
        return new ArrayList<>(paths);
    }
}
