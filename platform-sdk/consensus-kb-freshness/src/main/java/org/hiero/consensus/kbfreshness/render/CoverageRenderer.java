// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.findings.InterfaceDiffAssembler;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * Renders the coverage lane: documentation gaps that are the inverse of drift and so are kept out of the
 * drift report by design. Three kinds are surfaced, each tracked separately for a curator closing gaps:
 * <ul>
 *   <li>code that exists but the KB does not document (e.g. an interface method absent from its entry);</li>
 *   <li>architecture topics that anchor no source — no mechanically-checkable claim;</li>
 *   <li>interface docs that do not opt into the Tier-2 method-set diff, so it never runs for them.</li>
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
        renderUnanchoredTopics(sb, result);
        renderUncheckedInterfaces(sb, result);
        return sb.toString();
    }

    /**
     * Section: code that exists but the KB does not document (the {@link Lane#COVERAGE_GAP} findings).
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void renderUndocumentedCode(final StringBuilder sb, final RunResult result) {
        sb.append("## Undocumented code\n\n");
        sb.append("_Code that exists but the KB does not document (e.g. an interface method not listed in "
                + "its entry)._\n\n");
        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.COVERAGE_GAP) {
                continue;
            }
            any = true;
            sb.append("- `")
                    .append(f.entryKey())
                    .append("` — ")
                    .append(f.evidence())
                    .append('\n');
        }
        sb.append(any ? "\n" : "_None._\n\n");
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
        sb.append("## Architecture topics anchoring no source\n\n");
        sb.append("_Topic docs that cite no resolvable source file, so no claim can be checked against code. "
                + "Consider anchoring them._\n\n");
        boolean any = false;
        for (final WorklistEntry e : result.worklist()) {
            if (e.entryPath().contains("/architecture/topics/") && e.anchoredSourceCount() == 0) {
                any = true;
                sb.append("- `")
                        .append(e.entryKey())
                        .append("` — `")
                        .append(e.entryPath())
                        .append("`\n");
            }
        }
        sb.append(any ? "\n" : "_None._\n\n");
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
        sb.append("## Interface docs not checked at Tier-2\n\n");
        sb.append("_`architecture/interfaces/*` docs without `interface:`/`methods:` frontmatter: their "
                + "method set is never mechanically diffed (left entirely to the semantic pass)._\n\n");
        boolean any = false;
        for (final KbDocument doc : result.documents()) {
            if (doc.entry().type() == EntryType.ARCHITECTURE_INTERFACE && !InterfaceDiffAssembler.optsIntoTier2(doc)) {
                any = true;
                sb.append("- `")
                        .append(doc.entry().key())
                        .append("` — `")
                        .append(doc.entry().relativePath())
                        .append("`\n");
            }
        }
        sb.append(any ? "\n" : "_None._\n\n");
    }
}
