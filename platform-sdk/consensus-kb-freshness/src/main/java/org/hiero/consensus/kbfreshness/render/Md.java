// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.List;

/**
 * Small shared Markdown-emit helpers for the renderers, capturing the two idioms repeated across them: a
 * headed-and-blurbed bulleted section that renders {@code _None._} when empty, and the per-finding header
 * (entry key, cited target, entry path, optional evidence). Keeping the exact spacing in one place stops
 * the near-clones from drifting apart. Package-private; the renderers own their content, these only lay
 * out the shared scaffolding.
 */
final class Md {

    /** Prevents instantiation of this static-only helper. */
    private Md() {}

    /**
     * Appends a section: a {@code ## heading}, an italic blurb, then the items as {@code - item} bullets,
     * or {@code _None._} when there are none.
     *
     * @param sb      the buffer to append to.
     * @param heading the section heading (without the {@code ##}).
     * @param blurb   the italic one-line description (without the surrounding underscores).
     * @param items   the pre-formatted bullet bodies (without the leading {@code - }); empty renders
     *                {@code _None._}.
     */
    static void bulletedSection(
            final StringBuilder sb, final String heading, final String blurb, final List<String> items) {
        sb.append("## ").append(heading).append("\n\n");
        sb.append('_').append(blurb).append("_\n\n");
        if (items.isEmpty()) {
            sb.append("_None._\n\n");
            return;
        }
        for (final String item : items) {
            sb.append("- ").append(item).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Appends the per-finding header shared by the suggestions and auto-fix renderers: a {@code ###} line
     * naming the entry key and cited target, then the entry path with optional trailing evidence.
     *
     * @param sb       the buffer to append to.
     * @param entryKey the citing entry's key.
     * @param target   the cited target (or other subject) shown after the entry key.
     * @param path     the entry's repo-relative path.
     * @param evidence the trailing evidence after the path, or {@code null} to omit it.
     */
    static void findingHeader(
            final StringBuilder sb,
            final String entryKey,
            final String target,
            final String path,
            final String evidence) {
        sb.append("### `").append(entryKey).append("` — `").append(target).append("`\n");
        sb.append("`").append(path).append("`");
        if (evidence != null) {
            sb.append(" — ").append(evidence);
        }
        sb.append("\n\n");
    }
}
