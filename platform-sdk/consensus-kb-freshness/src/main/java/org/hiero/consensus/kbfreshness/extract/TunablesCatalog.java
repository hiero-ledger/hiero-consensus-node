// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.util.Markdown;
import org.hiero.consensus.kbfreshness.util.Patterns;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * Parses the structure of the tunables catalog ({@code tunables.md}): one section per config record —
 * heading {@code ## `<prefix>.*` — <ClassName>} or {@code ## <ClassName> (no prefix)} — with its
 * {@code Module:}/{@code Source:} line and the {@code TUN-NNN} table rows (key, type, default).
 * Only shapes the catalog's own column conventions mandate are read; anything looser is left to the
 * generic anchor extractor and the semantic pass.
 */
public final class TunablesCatalog {

    /** Matches a prefixed section heading, capturing the config prefix and the record class name. */
    private static final Pattern PREFIXED_HEADING = Pattern.compile(
            "^##\\s+`([A-Za-z0-9_.]+)\\.\\*`\\s*" + Patterns.DASH_SEP + "\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");

    /** Matches a no-prefix section heading, capturing the record class name. */
    private static final Pattern BARE_HEADING =
            Pattern.compile("^##\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+\\(no prefix\\)\\s*$");

    /** Matches any other level-2 heading (ends the current section without starting one). */
    private static final Pattern OTHER_HEADING = Pattern.compile("^##\\s+.*$");

    /** Matches the section's {@code Source: [text](url)} link, capturing the URL. */
    private static final Pattern SOURCE_LINK = Pattern.compile("Source:\\s*\\[[^\\]]*\\]\\(([^)\\s]+)\\)");

    /** Matches a {@code TUN-NNN} table row, capturing the ID and the key, type, and default cells. */
    private static final Pattern ROW = Pattern.compile("^\\|\\s*(TUN-\\d{3})\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|");

    /**
     * One config-record section of the catalog.
     *
     * @param prefix      the config prefix the heading documents ({@code ""} for a no-prefix record).
     * @param className   the config record's simple class name from the heading.
     * @param headingLine the 1-based line of the section heading.
     * @param moduleLabel the {@code Module:} label value, or {@code null} when the section has none.
     * @param sourcePath  the repo-relative path the {@code Source:} link resolves to, or {@code null}.
     * @param sourceLine  the 1-based line of the {@code Source:} link, or {@code 0} when absent.
     * @param rows        the section's {@code TUN-NNN} rows, in document order.
     */
    public record Section(
            String prefix,
            String className,
            int headingLine,
            String moduleLabel,
            String sourcePath,
            int sourceLine,
            List<Row> rows) {}

    /**
     * One tunable row of a section's table. Cell text is trimmed with surrounding backticks stripped.
     *
     * @param id           the {@code TUN-NNN} catalog ID.
     * @param key          the fully-qualified config key as documented.
     * @param type         the documented Java type (may be blank).
     * @param defaultValue the documented default literal (may be blank).
     * @param line         the 1-based line of the row.
     */
    public record Row(String id, String key, String type, String defaultValue, int line) {}

    /** Prevents instantiation of this static-only parser. */
    private TunablesCatalog() {}

    /**
     * Parses a tunables-catalog document into its config-record sections.
     *
     * @param doc the tunables catalog document.
     * @return the sections in document order (possibly empty).
     */
    public static List<Section> parse(final KbDocument doc) {
        final String docDir = RepoPaths.parentDir(doc.entry().relativePath());
        final SectionBuilder builder = new SectionBuilder();
        boolean inFence = false;

        final List<String> lines = doc.lines();
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            final int fileLine = i + 1;
            if (Markdown.isFenceDelimiter(line)) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }

            final Matcher prefixed = PREFIXED_HEADING.matcher(line);
            final Matcher bare = BARE_HEADING.matcher(line);
            if (prefixed.matches() || bare.matches()) {
                final boolean isPrefixed = prefixed.matches();
                builder.start(
                        isPrefixed ? prefixed.group(1) : "", isPrefixed ? prefixed.group(2) : bare.group(1), fileLine);
                continue;
            }
            if (OTHER_HEADING.matcher(line).matches()) {
                builder.flush();
                continue;
            }
            if (!builder.inSection()) {
                continue;
            }

            if (!builder.hasSource()) {
                final Matcher src = SOURCE_LINK.matcher(line);
                if (src.find()) {
                    final Matcher mod = Patterns.MODULE_LABEL.matcher(line);
                    builder.source(
                            RepoPaths.resolveRelative(docDir, src.group(1)),
                            fileLine,
                            mod.find() ? mod.group(1) : null);
                }
            }
            final Matcher row = ROW.matcher(line);
            if (row.find()) {
                builder.addRow(
                        new Row(row.group(1), cell(row.group(2)), cell(row.group(3)), cell(row.group(4)), fileLine));
            }
        }
        builder.flush();
        return builder.sections();
    }

    /**
     * Normalizes a table cell: trimmed, with one surrounding backtick pair stripped and the HTML
     * entities the catalog uses to escape generics in table cells ({@code &lt;}, {@code &gt;},
     * {@code &amp;}) decoded.
     *
     * @param raw the raw cell text.
     * @return the cell value.
     */
    private static String cell(final String raw) {
        String t = raw.strip();
        if (t.length() >= 2 && t.startsWith("`") && t.endsWith("`")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /**
     * Accumulates the fields of the section currently being parsed and {@link #flush() flushes} them into a
     * {@link Section}. Centralizing the {@code new Section(...)} construction in one place keeps the
     * heading, next-heading, and end-of-document flush paths from diverging.
     */
    private static final class SectionBuilder {

        /** The sections completed so far, in document order. */
        private final List<Section> sections = new ArrayList<>();

        /** Whether a section heading has been seen with no terminating heading yet. */
        private boolean inSection;

        /** The current section's config prefix ({@code ""} for a no-prefix section). */
        private String prefix;

        /** The current section's record class name from the heading. */
        private String className;

        /** The 1-based line of the current section's heading. */
        private int headingLine;

        /** The current section's {@code Module:} label, or {@code null}. */
        private String moduleLabel;

        /** The current section's resolved {@code Source:} path, or {@code null} until seen. */
        private String sourcePath;

        /** The 1-based line of the current section's {@code Source:} link, or {@code 0}. */
        private int sourceLine;

        /** The current section's {@code TUN-NNN} rows, in document order. */
        private List<Row> rows;

        /**
         * Flushes any in-progress section into the accumulated list, then begins a new one.
         *
         * @param prefix      the config prefix ({@code ""} for a no-prefix section).
         * @param className   the record class name from the heading.
         * @param headingLine the 1-based line of the heading.
         */
        void start(final String prefix, final String className, final int headingLine) {
            flush();
            inSection = true;
            this.prefix = prefix;
            this.className = className;
            this.headingLine = headingLine;
            moduleLabel = null;
            sourcePath = null;
            sourceLine = 0;
            rows = new ArrayList<>();
        }

        /**
         * Records the current section's {@code Source:} link and the module label on its line. Only the
         * first {@code Source:} line counts — guard the call with {@link #hasSource()}.
         *
         * @param sourcePath  the repo-relative path the {@code Source:} link resolves to.
         * @param sourceLine  the 1-based line of the {@code Source:} link.
         * @param moduleLabel the {@code Module:} label on the same line, or {@code null} when absent.
         */
        void source(final String sourcePath, final int sourceLine, final String moduleLabel) {
            this.sourcePath = sourcePath;
            this.sourceLine = sourceLine;
            this.moduleLabel = moduleLabel;
        }

        /**
         * Appends a documented row to the current section.
         *
         * @param row the row to add.
         */
        void addRow(final Row row) {
            rows.add(row);
        }

        /** Whether a section is currently being accumulated. */
        boolean inSection() {
            return inSection;
        }

        /** Whether the current section's {@code Source:} link has already been recorded. */
        boolean hasSource() {
            return sourcePath != null;
        }

        /** Flushes the in-progress section (if any) into the accumulated list. */
        void flush() {
            if (inSection) {
                sections.add(new Section(prefix, className, headingLine, moduleLabel, sourcePath, sourceLine, rows));
                inSection = false;
            }
        }

        /** The completed sections, in document order. */
        List<Section> sections() {
            return sections;
        }
    }
}
