// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.hiero.consensus.kbfreshness.extract.AnchorExtractor;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.Entry;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.resolve.AnchorResolver;
import org.hiero.consensus.kbfreshness.resolve.Resolution;

/**
 * Turns extracted anchors into collapsed findings. Anchors sharing {@code (entry, target, kind)}
 * collapse into one finding whose occurrences are their line hints; the finding's identity is a stable
 * hash of that triple, with no line numbers, so it survives file moves and code renames until the KB
 * text itself changes. A finding is emitted only when the collapsed group resolves to a lane (assert,
 * quiet-log, or auto-fix); a clean present group produces nothing.
 */
public final class FindingAssembler {

    /** Canonical deterministic ordering of findings: by entry key, kind, target, then id. */
    public static final Comparator<Finding> ORDER = Comparator.comparing(Finding::entryKey)
            .thenComparing(f -> f.kind().name())
            .thenComparing(Finding::target)
            .thenComparing(Finding::id);

    /** Extracts anchors from a KB document. */
    private final AnchorExtractor extractor;

    /** Resolves an anchor to an outcome and lane. */
    private final AnchorResolver resolver;

    /**
     * Creates an assembler from its collaborators.
     *
     * @param extractor the anchor extractor.
     * @param resolver  the anchor resolver.
     */
    public FindingAssembler(final AnchorExtractor extractor, final AnchorResolver resolver) {
        this.extractor = extractor;
        this.resolver = resolver;
    }

    /**
     * Assembles all findings for every document, sorted deterministically.
     *
     * @param docs the KB documents to process.
     * @return the collapsed findings across all documents, sorted by entry key, kind, target, then id.
     */
    public List<Finding> assembleAll(final List<KbDocument> docs) {
        final List<Finding> all = new ArrayList<>();
        for (final KbDocument doc : docs) {
            all.addAll(assemble(doc));
        }
        all.sort(ORDER);
        return all;
    }

    /**
     * Assembles the findings for a single document, grouping anchors by {@code (target, kind)}.
     *
     * @param doc the KB document to process.
     * @return the collapsed findings for the document.
     */
    List<Finding> assemble(final KbDocument doc) {
        final Entry entry = doc.entry();
        final List<Anchor> anchors = extractor.extract(doc);

        // Group by (kind, target) within the entry, preserving first-seen order. The space delimiter keeps
        // the composite key unambiguous: no anchor-kind name contains a space or is a prefix of another.
        final Map<String, List<Anchor>> groups = new LinkedHashMap<>();
        for (final Anchor a : anchors) {
            groups.computeIfAbsent(a.kind().name() + " " + a.target(), k -> new ArrayList<>())
                    .add(a);
        }

        final List<Finding> findings = new ArrayList<>();
        for (final List<Anchor> group : groups.values()) {
            final Finding f = collapse(entry, group);
            if (f != null) {
                findings.add(f);
            }
        }
        return findings;
    }

    /**
     * Collapses one anchor group into at most one finding. If the group's existence resolution emits a
     * finding it is returned directly; otherwise a present-and-clean group yields an auto-fix finding for
     * moved line references, or {@code null} when there is nothing to report.
     *
     * @param entry the KB entry the group belongs to.
     * @param group the anchors sharing a {@code (target, kind)}, first-seen order.
     * @return the collapsed finding, or {@code null} if the group is clean.
     */
    private Finding collapse(final Entry entry, final List<Anchor> group) {
        final Anchor rep = group.get(0);
        final Resolution existence = resolver.resolve(withoutLine(rep));

        if (existence.emitsFinding()) {
            return build(entry, rep, existence, occurrencesOf(group));
        }

        // Present-and-clean group: the only remaining finding is an auto-fix for a cited line — either a
        // moved line (corrected line) or a declaration line migrating to `#symbol`.
        final List<Occurrence> autoFixOccurrences = new ArrayList<>();
        Integer correctedLine = null;
        String correctedSymbol = null;
        String evidence = "";
        String question = existence.question();
        for (final Anchor a : group) {
            final Resolution r = resolver.resolve(a);
            if (r.lane() == Lane.AUTO_FIX) {
                autoFixOccurrences.add(a.toOccurrence());
                correctedLine = r.autoFixLine();
                correctedSymbol = r.autoFixSymbol();
                evidence = r.evidence();
                question = r.question();
            }
        }
        if (autoFixOccurrences.isEmpty()) {
            return null;
        }
        autoFixOccurrences.sort(Comparator.naturalOrder());
        final Resolution autoFix = new Resolution(
                existence.outcome(), Lane.AUTO_FIX, question, evidence, correctedLine, null, correctedSymbol);
        return build(entry, rep, autoFix, autoFixOccurrences);
    }

    /**
     * Builds a finding from a representative anchor and its resolution, computing the stable identity hash.
     *
     * @param entry       the KB entry.
     * @param rep         the representative anchor for the group.
     * @param res         the resolution supplying outcome, lane, question, evidence and auto-fix line.
     * @param occurrences the occurrences to attach.
     * @return the assembled finding.
     */
    private Finding build(
            final Entry entry, final Anchor rep, final Resolution res, final List<Occurrence> occurrences) {
        return Finding.of(
                        entry,
                        rep.kind(),
                        rep.target(),
                        rep.citedModule(),
                        rep.citedScope(),
                        res.outcome(),
                        res.lane(),
                        res.question(),
                        res.evidence(),
                        occurrences)
                .withAutoFixLine(res.autoFixLine())
                .withResolvedPath(res.resolvedPath())
                .withStatedModule(rep.statedModule())
                .withAutoFixSymbol(res.autoFixSymbol());
    }

    /**
     * Collects the distinct occurrences of a group, sorted in natural order.
     *
     * @param group the anchors to collect occurrences from.
     * @return the deduplicated, sorted occurrences.
     */
    private static List<Occurrence> occurrencesOf(final List<Anchor> group) {
        final TreeSet<Occurrence> set = new TreeSet<>();
        for (final Anchor a : group) {
            set.add(a.toOccurrence());
        }
        return new ArrayList<>(set);
    }

    /**
     * Returns a copy of the anchor with its cited line stripped, used to resolve existence independently
     * of line references.
     *
     * @param a the anchor to copy.
     * @return an equivalent anchor with no cited line.
     */
    private static Anchor withoutLine(final Anchor a) {
        return new Anchor(
                a.kind(),
                a.target(),
                a.citedModule(),
                a.citedScope(),
                a.docLine(),
                Anchor.NO_LINE,
                a.rawText(),
                a.statedModule(),
                a.historical());
    }
}
