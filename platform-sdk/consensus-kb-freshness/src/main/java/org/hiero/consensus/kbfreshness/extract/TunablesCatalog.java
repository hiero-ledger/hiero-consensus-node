// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the structure of the tunables catalog ({@code tunables.md}): one section per config record —
 * heading {@code ## `<prefix>.*` — <ClassName>} or {@code ## <ClassName> (no prefix)} — with its
 * {@code Module:}/{@code Source:} line and the {@code TUN-NNN} table rows (key, type, default).
 * Only shapes the catalog's own column conventions mandate are read; anything looser is left to the
 * generic anchor extractor and the semantic pass.
 */
public final class TunablesCatalog {

    /** Matches a prefixed section heading, capturing the config prefix and the record class name. */
    private static final Pattern PREFIXED_HEADING =
            Pattern.compile("^##\\s+`([A-Za-z0-9_.]+)\\.\\*`\\s*(?:\\u2014|--)\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");

    /** Matches a no-prefix section heading, capturing the record class name. */
    private static final Pattern BARE_HEADING =
            Pattern.compile("^##\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+\\(no prefix\\)\\s*$");

    /** Matches any other level-2 heading (ends the current section without starting one). */
    private static final Pattern OTHER_HEADING = Pattern.compile("^##\\s+.*$");

    /** Matches the section's prose {@code Module: `name`} label, capturing the module name. */
    private static final Pattern MODULE_LABEL = Pattern.compile("Module:\\s*`([A-Za-z0-9._-]+)`");

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
        final List<Section> sections = new ArrayList<>();
        final String docDir = parentDir(doc.entry().relativePath());
        String prefix = null;
        String className = null;
        int headingLine = 0;
        String moduleLabel = null;
        String sourcePath = null;
        int sourceLine = 0;
        List<Row> rows = new ArrayList<>();
        boolean inSection = false;
        boolean inFence = false;

        final List<String> lines = doc.lines();
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            final int fileLine = i + 1;
            final String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }

            final Matcher prefixed = PREFIXED_HEADING.matcher(line);
            final Matcher bare = BARE_HEADING.matcher(line);
            if (prefixed.matches() || bare.matches()) {
                if (inSection) {
                    sections.add(
                            new Section(prefix, className, headingLine, moduleLabel, sourcePath, sourceLine, rows));
                }
                inSection = true;
                prefix = prefixed.matches() ? prefixed.group(1) : "";
                className = prefixed.matches() ? prefixed.group(2) : bare.group(1);
                headingLine = fileLine;
                moduleLabel = null;
                sourcePath = null;
                sourceLine = 0;
                rows = new ArrayList<>();
                continue;
            }
            if (OTHER_HEADING.matcher(line).matches()) {
                if (inSection) {
                    sections.add(
                            new Section(prefix, className, headingLine, moduleLabel, sourcePath, sourceLine, rows));
                    inSection = false;
                }
                continue;
            }
            if (!inSection) {
                continue;
            }

            if (sourcePath == null) {
                final Matcher src = SOURCE_LINK.matcher(line);
                if (src.find()) {
                    sourcePath = AnchorExtractor.resolveRelative(docDir, src.group(1));
                    sourceLine = fileLine;
                    final Matcher mod = MODULE_LABEL.matcher(line);
                    if (mod.find()) {
                        moduleLabel = mod.group(1);
                    }
                }
            }
            final Matcher row = ROW.matcher(line);
            if (row.find()) {
                rows.add(new Row(row.group(1), cell(row.group(2)), cell(row.group(3)), cell(row.group(4)), fileLine));
            }
        }
        if (inSection) {
            sections.add(new Section(prefix, className, headingLine, moduleLabel, sourcePath, sourceLine, rows));
        }
        return sections;
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
     * Returns the parent directory portion of a repo-relative path.
     *
     * @param repoRelPath the repo-relative path.
     * @return the parent directory, or an empty string if the path has no directory component.
     */
    private static String parentDir(final String repoRelPath) {
        final int slash = repoRelPath.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? repoRelPath.substring(0, slash) : "";
    }
}
