// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.util;

import java.util.regex.Pattern;

/**
 * Regex constants and fragments shared across more than one scanner/renderer, kept in one place so the
 * vocabulary they encode — ISO dates, the {@code Module:} label, the em-dash cell separator, and the
 * catalog-ID prefix sets — cannot drift between call sites.
 */
public final class Patterns {

    /** Prevents instantiation of this constants holder. */
    private Patterns() {}

    /** Matches an ISO {@code yyyy-MM-dd} date (a {@code last_reviewed} marker or a mark-reviewed date). */
    public static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Matches a {@code Module: `name`} label, capturing the stated module name. */
    public static final Pattern MODULE_LABEL = Pattern.compile("Module:\\s*`([A-Za-z0-9._-]+)`");

    /** Separator between two heading/verification cells: an em dash (U+2014) or a double hyphen. */
    public static final String DASH_SEP = "(?:\\u2014|--)";

    /**
     * Catalog-ID prefixes that get their own markdown file ({@code ADR}/{@code INV}/{@code RUL}/
     * {@code SCN}/{@code HEU}) — the set a KB filename may start with.
     */
    public static final String FILE_CATALOG_PREFIXES = "ADR|INV|RUL|SCN|HEU";

    /**
     * All catalog-ID prefixes citable inline: the per-file ones plus the two table-row catalogs
     * ({@code SYM} in {@code symptoms.md}, {@code TUN} in {@code tunables.md}), which have no per-ID file.
     */
    public static final String ALL_CATALOG_PREFIXES = FILE_CATALOG_PREFIXES + "|SYM|TUN";
}
