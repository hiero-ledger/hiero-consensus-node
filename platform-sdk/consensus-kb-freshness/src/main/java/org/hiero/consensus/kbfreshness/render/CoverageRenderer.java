// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.findings.InterfaceDiffAssembler;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * Renders the coverage lane: documentation gaps that are the inverse of drift and so are kept out of the
 * drift report by design. Five kinds are surfaced, each tracked separately for a curator closing gaps:
 * <ul>
 *   <li>code that exists but the KB does not document (e.g. an interface method absent from its entry);</li>
 *   <li>in-scope config records the tunables catalog has no section for at all;</li>
 *   <li>architecture topics that anchor no source — no mechanically-checkable claim;</li>
 *   <li>interface docs that do not opt into the Tier-2 method-set diff, so it never runs for them;</li>
 *   <li>cited topic slugs whose document does not exist — the topic may be worth writing.</li>
 * </ul>
 * None of these is drift; none is ever asserted.
 */
public final class CoverageRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private CoverageRenderer() {}

    /**
     * Renders the coverage lane as Markdown.
     *
     * @param result the run result.
     * @return the rendered Markdown coverage report.
     */
    public static String render(final RunResult result) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — coverage lane (documentation gaps)\n\n");
        sb.append("_The inverse of drift: code the KB does not describe, and KB docs that carry no "
                + "mechanically-checkable anchor. Not drift; never asserted._\n\n");
        renderUndocumentedCode(sb, result);
        renderUndocumentedRecords(sb, result);
        renderUnanchoredTopics(sb, result);
        renderUncheckedInterfaces(sb, result);
        renderMissingTopicDocs(sb, result);
        return sb.toString();
    }

    /**
     * Section: code that exists but the KB does not document (the {@link Lane#COVERAGE_GAP} findings,
     * except whole undocumented config records, which get their own section below).
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void renderUndocumentedCode(final StringBuilder sb, final RunResult result) {
        final List<String> items = new ArrayList<>();
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.COVERAGE_GAP || f.kind() == AnchorKind.CONFIG_PREFIX) {
                continue;
            }
            items.add("`" + f.entryKey() + "` — " + f.evidence());
        }
        Md.bulletedSection(
                sb,
                "Undocumented code",
                "Code that exists but the KB does not document (e.g. an interface method not listed in "
                        + "its entry).",
                items);
    }

    /**
     * Section: in-scope config records the tunables catalog carries no section for. Keys that migrate
     * into a brand-new config record would otherwise vanish from coverage entirely — the old key asserts
     * as gone, but nothing would say the successor record is undocumented.
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void renderUndocumentedRecords(final StringBuilder sb, final RunResult result) {
        final List<String> items = new ArrayList<>();
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.COVERAGE_GAP || f.kind() != AnchorKind.CONFIG_PREFIX) {
                continue;
            }
            items.add(f.evidence());
        }
        Md.bulletedSection(
                sb,
                "Config records with no tunables section",
                "`@ConfigData` records in consensus-layer (or already-documented) modules that the "
                        + "tunables catalog has no section for — candidate sections to write.",
                items);
    }

    /**
     * Section: architecture topics that anchor no source. A topic doc citing no resolvable source file has
     * no code-anchored claim the engine (or the semantic pass) can check against, so it is a documentation
     * gap worth closing. Interface docs are excluded — their coverage is reported by
     * {@link #renderUncheckedInterfaces}.
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void renderUnanchoredTopics(final StringBuilder sb, final RunResult result) {
        final List<String> items = new ArrayList<>();
        for (final WorklistEntry e : result.worklist()) {
            if (e.entryPath().contains("/architecture/topics/") && e.anchoredSourceCount() == 0) {
                items.add("`" + e.entryKey() + "` — `" + e.entryPath() + "`");
            }
        }
        Md.bulletedSection(
                sb,
                "Architecture topics anchoring no source",
                "Topic docs that cite no resolvable source file, so no claim can be checked against code. "
                        + "Consider anchoring them.",
                items);
    }

    /**
     * Section: cited topic slugs whose document does not exist. Each is already asserted as drift in the
     * report; this lens groups them by slug as documentation gaps — when several entries tag a topic
     * that was never written, the fix may be to write it rather than retarget every citation.
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void renderMissingTopicDocs(final StringBuilder sb, final RunResult result) {
        final Map<String, List<String>> citersBySlug = new TreeMap<>();
        for (final Finding f : result.findings()) {
            if (f.kind() == AnchorKind.CROSS_DOC_LINK
                    && f.outcome() == Outcome.ABSENT
                    && f.lane() == Lane.ASSERT
                    && f.target().contains("/architecture/topics/")) {
                final String name = f.target().substring(f.target().lastIndexOf('/') + 1);
                final String slug = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
                final List<String> citers = citersBySlug.computeIfAbsent(slug, k -> new ArrayList<>());
                if (!citers.contains(f.entryKey())) {
                    citers.add(f.entryKey());
                }
            }
        }
        final List<String> items = new ArrayList<>();
        for (final Map.Entry<String, List<String>> e : citersBySlug.entrySet()) {
            items.add("`" + e.getKey() + "` — cited by " + e.getValue().size() + ": "
                    + String.join(", ", e.getValue().stream().sorted().toList()));
        }
        Md.bulletedSection(
                sb,
                "Cited topic slugs with no document",
                "Frontmatter `topics:` tags (and topic links) whose target document does not exist — "
                        + "candidate topics to write, or slugs to retarget (see `suggestions.md`).",
                items);
    }

    /**
     * Section: interface docs that do not opt into the Tier-2 method-set diff. Without {@code interface:}
     * and {@code methods:} frontmatter the diff never runs, so "no interface findings" would otherwise be
     * indistinguishable from "the check never fired". Surfacing them makes the dormancy visible.
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void renderUncheckedInterfaces(final StringBuilder sb, final RunResult result) {
        final List<String> items = new ArrayList<>();
        for (final KbDocument doc : result.documents()) {
            if (doc.entry().type() == EntryType.ARCHITECTURE_INTERFACE && !InterfaceDiffAssembler.optsIntoTier2(doc)) {
                items.add("`" + doc.entry().key() + "` — `" + doc.entry().relativePath() + "`");
            }
        }
        Md.bulletedSection(
                sb,
                "Interface docs not checked at Tier-2",
                "`architecture/interfaces/*` docs without `interface:`/`methods:` frontmatter: their "
                        + "method set is never mechanically diffed (left entirely to the semantic pass).",
                items);
    }
}
