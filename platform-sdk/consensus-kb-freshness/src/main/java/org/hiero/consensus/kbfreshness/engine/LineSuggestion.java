// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

/**
 * A body-line source reference (line NN is inside a method, not a declaration) or a past-end-of-file
 * reference — a {@code File.java:NN} that {@code --fix} cannot migrate to a symbol. The checker suggests
 * citing the enclosing symbol instead of the volatile line. Advisory only: rendered in {@code
 * suggestions.md}, never asserted or auto-applied. Deterministic (parsed from the current file).
 *
 * @param entryKey        the citing entry's key.
 * @param entryPath       the citing entry's repo-relative path.
 * @param docLine         the 1-based KB line of the citation.
 * @param basename        the cited file's basename.
 * @param citedLine       the cited 1-based source line.
 * @param enclosingSymbol the nearest declaration enclosing the cited line, or {@code null} when the line
 *                        is past end-of-file or no declaration precedes it.
 * @param fileLineCount   the cited file's current line count (for the past-EOF message).
 */
public record LineSuggestion(
        String entryKey,
        String entryPath,
        int docLine,
        String basename,
        int citedLine,
        String enclosingSymbol,
        int fileLineCount) {

    /**
     * Whether the cited line is beyond the file's current end.
     *
     * @return {@code true} when the cited line exceeds the file's line count.
     */
    public boolean pastEof() {
        return citedLine > fileLineCount;
    }
}
