// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;

/**
 * Renders deterministic auto-fix proposals: a cited symbol still resolves, but a line reference moved.
 * Line numbers are never asserted on — instead the corrected line is proposed as a before/after edit
 * for the curator to apply. Nothing is written to the KB; these are suggestions only.
 */
public final class AutoFixRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private AutoFixRenderer() {}

    /**
     * Renders the auto-fix line-reference proposals as Markdown.
     *
     * @param result the run result.
     * @return the rendered Markdown proposals.
     */
    public static String render(final RunResult result) {
        final Map<String, KbDocument> byKey = new HashMap<>();
        for (final KbDocument d : result.documents()) {
            byKey.put(d.entry().key(), d);
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — auto-fix proposals (line references)\n\n");
        sb.append("_The symbol still resolves; only the cited line moved. Suggestions only — apply by hand._\n\n");

        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.AUTO_FIX || f.autoFixLine() == null) {
                continue;
            }
            any = true;
            sb.append("### `")
                    .append(f.entryKey())
                    .append("` — `")
                    .append(f.target())
                    .append("`\n");
            sb.append("`")
                    .append(f.entryPath())
                    .append("` — ")
                    .append(f.evidence())
                    .append("\n\n");
            final KbDocument doc = byKey.get(f.entryKey());
            for (final Occurrence o : f.occurrences()) {
                final int corrected = f.autoFixLine();
                sb.append("- KB line ")
                        .append(o.docLine())
                        .append(": update `:")
                        .append(o.citedLine())
                        .append("` → `:")
                        .append(corrected)
                        .append("`\n");
                final String before = docLine(doc, o.docLine());
                if (before != null && o.citedLine() >= 0) {
                    final String after = before.replace(":" + o.citedLine(), ":" + corrected);
                    if (!after.equals(before)) {
                        sb.append("  ```diff\n");
                        sb.append("  - ").append(before).append('\n');
                        sb.append("  + ").append(after).append('\n');
                        sb.append("  ```\n");
                    }
                }
            }
            sb.append('\n');
        }
        if (!any) {
            sb.append("_None._\n");
        }
        return sb.toString();
    }

    /**
     * Returns a document's 1-based line text, or {@code null} when the document is missing or the line is
     * out of range.
     *
     * @param doc  the document, possibly {@code null}.
     * @param line the 1-based line number.
     * @return the line text, or {@code null} if unavailable.
     */
    private static String docLine(final KbDocument doc, final int line) {
        if (doc == null) {
            return null;
        }
        final List<String> lines = doc.lines();
        return (line >= 1 && line <= lines.size()) ? lines.get(line - 1) : null;
    }
}
