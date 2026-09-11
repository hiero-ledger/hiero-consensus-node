// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.util.Json;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;

/**
 * Renders the semantic worklist that scopes the Tier-3 (model) pass. The skill reads the JSON form and
 * re-reads only topics whose status is {@code review} or {@code unknown}; the Markdown form is for
 * humans.
 */
public final class WorklistRenderer {

    /** The schema identifier written into the JSON worklist. */
    public static final String SCHEMA = "kb-freshness/worklist/v1";

    /** Prevents instantiation of this static-only renderer. */
    private WorklistRenderer() {}

    /**
     * Renders the semantic worklist as human-readable Markdown.
     *
     * @param result the run result.
     * @return the rendered Markdown worklist.
     */
    public static String renderMarkdown(final RunResult result) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — semantic worklist\n\n");
        sb.append("_Topics whose anchored source changed since `last_reviewed`. ")
                .append("The semantic pass processes `review` and `unknown` rows only._\n\n");
        sb.append("| Topic | Status | last_reviewed | Changed sources |\n|---|---|---|---|\n");
        for (final WorklistEntry e : result.worklist()) {
            sb.append("| ")
                    .append(e.entryKey())
                    .append(" | ")
                    .append(e.status().name().toLowerCase(Locale.ROOT))
                    .append(e.note() == null ? "" : " (" + e.note() + ")")
                    .append(" | ")
                    .append(e.lastReviewed() == null ? "—" : e.lastReviewed())
                    .append(" | ")
                    .append(
                            e.changedPaths().isEmpty()
                                    ? "—"
                                    : String.valueOf(e.changedPaths().size()))
                    .append(" |\n");
        }
        sb.append('\n');
        for (final WorklistEntry e : result.worklist()) {
            if (!e.changedPaths().isEmpty()) {
                sb.append("### ").append(e.entryKey()).append("\n");
                for (final String p : e.changedPaths()) {
                    sb.append("- `").append(p).append("`\n");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Renders the semantic worklist as the machine-readable JSON consumed by the Tier-3 pass.
     *
     * @param result the run result.
     * @return the rendered JSON worklist.
     */
    public static String renderJson(final RunResult result) {
        final List<Object> items = new ArrayList<>();
        for (final WorklistEntry e : result.worklist()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryKey", e.entryKey());
            m.put("entryPath", e.entryPath());
            m.put("lastReviewed", e.lastReviewed());
            m.put("status", e.status().name().toLowerCase(Locale.ROOT));
            m.put("note", e.note());
            m.put("anchoredSourceCount", e.anchoredSourceCount());
            m.put("newestAnchoredCommit", e.newestAnchoredCommit());
            m.put("changedPaths", new ArrayList<Object>(e.changedPaths()));
            items.add(m);
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("worklist", items);
        return Json.write(root);
    }
}
