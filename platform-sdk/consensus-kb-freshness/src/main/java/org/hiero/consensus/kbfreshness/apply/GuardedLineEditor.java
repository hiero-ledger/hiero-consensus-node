// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A minimal in-memory editor over a text file's lines that preserves each line's terminator. It
 * centralizes the read → split → CR-preserve → rewrite → join plumbing shared by {@link AutoFixApplier}
 * and {@link ReviewedMarker}, so the CRLF subtlety — a trailing {@code \r} on a line must survive a
 * rewrite unchanged — lives in exactly one place and cannot diverge between the two writers. The guards
 * (which line to touch, and whether its current text still matches) stay with each caller, because they
 * differ: the applier requires an exact before-match, while the marker requires a {@code key:} prefix.
 */
final class GuardedLineEditor {

    /** The file this editor was opened over. */
    private final Path file;
    /** The file's lines, split on {@code \n} with a trailing-empty limit; each may keep a trailing {@code \r}. */
    private final String[] lines;
    /** Whether any line has been rewritten since opening. */
    private boolean changed;

    /**
     * Creates an editor over a file's already-split lines.
     *
     * @param file  the file the buffer was read from.
     * @param lines the file's lines, each possibly carrying a trailing carriage return.
     */
    private GuardedLineEditor(final Path file, final String[] lines) {
        this.file = file;
        this.lines = lines;
    }

    /**
     * Opens an editor over the given file, reading it as UTF-8.
     *
     * @param file the file to edit.
     * @return the editor holding the file's current content.
     * @throws IOException if reading the file fails.
     */
    static GuardedLineEditor open(final Path file) throws IOException {
        final String content = Files.readString(file, StandardCharsets.UTF_8);
        // Trailing-empty limit so a final newline round-trips; each element keeps its own terminator.
        return new GuardedLineEditor(file, content.split("\n", -1));
    }

    /**
     * Whether a 1-based line number is within the file.
     *
     * @param line the 1-based line number.
     * @return {@code true} when the line exists.
     */
    boolean hasLine(final int line) {
        return line >= 1 && line <= lines.length;
    }

    /**
     * The bare text of a line — its content without any trailing carriage return.
     *
     * @param line the 1-based line number (must exist).
     * @return the line's text sans a trailing {@code \r}.
     */
    String bareLine(final int line) {
        final String raw = lines[line - 1];
        final int cr = raw.endsWith("\r") ? 1 : 0;
        return raw.substring(0, raw.length() - cr);
    }

    /**
     * Rewrites a line's bare text, re-applying its original terminator, and marks the buffer changed.
     *
     * @param line the 1-based line number (must exist).
     * @param bare the new bare text.
     */
    void rewriteLine(final int line, final String bare) {
        final String raw = lines[line - 1];
        final String cr = raw.endsWith("\r") ? "\r" : "";
        lines[line - 1] = bare + cr;
        changed = true;
    }

    /**
     * Whether any line has been rewritten since opening.
     *
     * @return {@code true} when at least one rewrite occurred.
     */
    boolean changed() {
        return changed;
    }

    /**
     * Writes the buffer back to the file when it has changed; a no-op otherwise.
     *
     * @throws IOException if writing the file fails.
     */
    void flush() throws IOException {
        if (changed) {
            Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        }
    }
}
