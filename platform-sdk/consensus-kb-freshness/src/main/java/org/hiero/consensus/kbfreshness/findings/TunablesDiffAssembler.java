// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.extract.TunablesCatalog;
import org.hiero.consensus.kbfreshness.extract.TunablesCatalog.Row;
import org.hiero.consensus.kbfreshness.extract.TunablesCatalog.Section;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ConfigComponent;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.TypeInfo;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.Hashing;

/**
 * Tier-1/2 tunables-catalog checks. The catalog's own column conventions make each row mechanically
 * checkable against the {@code @ConfigData} record it documents: the key must be a declared
 * {@code @ConfigProperty} (Tier 1), and the documented default is the {@code defaultValue} literal
 * verbatim (Tier 2). Per the precision mandate:
 * <ul>
 *   <li>a documented key the resolved record no longer declares → {@code assert};</li>
 *   <li>a documented default that no longer matches a plain-literal {@code defaultValue} → {@code assert};</li>
 *   <li>a documented type that differs from the declared component type → quiet log only (the catalog
 *       documents semantic types, e.g. {@code Path} for a {@code String}-typed key, so a mismatch is
 *       not certainly drift);</li>
 *   <li>a non-literal {@code defaultValue} (constant reference) → quiet log (never compared as fact);</li>
 *   <li>a declared property the section does not document → coverage lane, never the drift report.</li>
 * </ul>
 * A section whose cited source is gone is additionally resolved <em>by config prefix</em>: when exactly
 * one indexed {@code *Config.java} record declares {@code @ConfigData} with the section's prefix and
 * declares every key the section documents, that is a certain class rename/move — asserted with the
 * resolved path so {@code --fix} can rewrite the heading, {@code Source:} link, and {@code Module:} label.
 */
public final class TunablesDiffAssembler {

    /** Source index used to resolve and parse the documented config records. */
    private final SourceIndex index;

    /** Lazily-built map of {@code @ConfigData} prefix to the indexed records declaring it. */
    private Map<String, List<ConfigOwner>> ownersByPrefix;

    /**
     * One indexed config record: where it lives and its parsed view.
     *
     * @param path      the repo-relative source path.
     * @param className the record's simple name.
     * @param type      the parsed type info (carries prefix and components).
     */
    private record ConfigOwner(String path, String className, TypeInfo type) {}

    /**
     * Creates an assembler over the given source index.
     *
     * @param index the source index for existence and parse lookups.
     */
    public TunablesDiffAssembler(final SourceIndex index) {
        this.index = index;
    }

    /**
     * Produces tunables-catalog findings for every tunable-catalog entry.
     *
     * @param docs the scanned KB documents.
     * @return the config key/default/prefix findings across all catalog entries.
     */
    public List<Finding> assembleAll(final List<KbDocument> docs) {
        final List<Finding> findings = new ArrayList<>();
        for (final KbDocument doc : docs) {
            if (doc.entry().type() == EntryType.TUNABLE_CATALOG) {
                for (final Section s : TunablesCatalog.parse(doc)) {
                    findings.addAll(checkSection(doc, s));
                }
            }
        }
        return findings;
    }

    /**
     * Checks one catalog section: resolves its config record (by cited path, unique basename, or unique
     * prefix owner) and diffs the documented rows against the record's declared properties.
     *
     * @param doc the tunables catalog document.
     * @param s   the section to check.
     * @return the section's findings (possibly empty).
     */
    private List<Finding> checkSection(final KbDocument doc, final Section s) {
        final List<Finding> findings = new ArrayList<>();
        final ConfigOwner owner = resolveRecord(doc, s, findings);
        if (owner == null) {
            return findings;
        }
        final String declaredPrefix = owner.type().configPrefix();
        if (declaredPrefix == null) {
            findings.add(prefixFinding(
                    doc,
                    s,
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    "`" + s.className() + "` in `" + owner.path()
                            + "` carries no `@ConfigData` annotation; its keys are unverifiable.",
                    null));
            return findings;
        }
        if (!declaredPrefix.equals(s.prefix())) {
            findings.add(prefixFinding(
                    doc,
                    s,
                    Outcome.ABSENT,
                    Lane.ASSERT,
                    "`" + s.className() + "` in `" + owner.path() + "` declares `@ConfigData(\"" + declaredPrefix
                            + "\")`, but the section documents prefix `" + headingPrefix(s) + "`.",
                    null));
            return findings;
        }
        checkRows(doc, s, owner, findings);
        return findings;
    }

    /**
     * Diffs a section's rows against the resolved record's declared properties: gone keys assert,
     * default mismatches assert (literal vs literal only), type differences and non-literal defaults go
     * to the quiet log, and undocumented properties land in the coverage lane.
     *
     * @param doc      the tunables catalog document.
     * @param s        the section being checked.
     * @param owner    the resolved config record.
     * @param findings the list to append findings to.
     */
    private static void checkRows(
            final KbDocument doc, final Section s, final ConfigOwner owner, final List<Finding> findings) {
        final String fqPrefix = s.prefix().isEmpty() ? "" : s.prefix() + ".";
        final Map<String, ConfigComponent> byKeyName = new LinkedHashMap<>();
        for (final ConfigComponent c : owner.type().configComponents()) {
            byKeyName.putIfAbsent(c.keyName(), c);
        }
        final List<String> documentedNames = new ArrayList<>();
        for (final Row row : s.rows()) {
            if (!fqPrefix.isEmpty() && !row.key().startsWith(fqPrefix)) {
                findings.add(keyFinding(
                        doc,
                        s,
                        owner,
                        row,
                        Outcome.UNVERIFIABLE,
                        Lane.QUIET_LOG,
                        "Row key `" + row.key() + "` does not start with the section prefix `" + fqPrefix
                                + "`; not checked."));
                continue;
            }
            final String propName = fqPrefix.isEmpty() ? row.key() : row.key().substring(fqPrefix.length());
            documentedNames.add(propName);
            final ConfigComponent c = byKeyName.get(propName);
            if (c == null) {
                findings.add(keyFinding(
                        doc,
                        s,
                        owner,
                        row,
                        Outcome.ABSENT,
                        Lane.ASSERT,
                        "Config record `" + owner.className() + "` (`@ConfigData(\"" + s.prefix() + "\")`) in `"
                                + owner.path() + "` declares no property `" + propName + "`; declared: "
                                + String.join(", ", byKeyName.keySet()) + "."));
                continue;
            }
            if (!row.type().isBlank() && !typeMatches(row.type(), c.type())) {
                findings.add(keyFinding(
                        doc,
                        s,
                        owner,
                        row,
                        Outcome.PRESENT,
                        Lane.QUIET_LOG,
                        "Documented type `" + row.type() + "` of `" + row.key() + "` differs from the declared `"
                                + c.type() + "` in `" + owner.className()
                                + "` — possibly a semantic type (not asserted)."));
            }
            checkDefault(doc, s, owner, row, c, findings);
        }
        for (final ConfigComponent c : owner.type().configComponents()) {
            if (!documentedNames.contains(c.keyName())) {
                final String fqKey = fqPrefix + c.keyName();
                findings.add(new Finding(
                        Hashing.id(doc.entry().key(), fqKey, AnchorKind.CONFIG_KEY.name()),
                        doc.entry().key(),
                        doc.entry().relativePath(),
                        doc.entry().type(),
                        AnchorKind.CONFIG_KEY,
                        fqKey,
                        moduleOf(owner.path()),
                        owner.className(),
                        Outcome.PRESENT,
                        Lane.COVERAGE_GAP,
                        "config record `" + owner.className() + "` property `" + c.keyName() + "` is documented",
                        "Config record `" + owner.className() + "` declares property `" + c.keyName() + "` (key `"
                                + fqKey + "`) not documented in this section.",
                        List.of(new Occurrence(s.headingLine(), Anchor.NO_LINE, fqKey)),
                        null,
                        null,
                        null));
            }
        }
    }

    /**
     * Compares a row's documented default against the component's {@code defaultValue}: equal literals
     * are clean, differing literals assert, and a non-literal source value is unverifiable. A blank
     * documented default is skipped — nothing is claimed.
     *
     * @param doc      the tunables catalog document.
     * @param s        the section being checked.
     * @param owner    the resolved config record.
     * @param row      the documented row.
     * @param c        the declared component the row documents.
     * @param findings the list to append findings to.
     */
    private static void checkDefault(
            final KbDocument doc,
            final Section s,
            final ConfigOwner owner,
            final Row row,
            final ConfigComponent c,
            final List<Finding> findings) {
        final String documented = row.defaultValue();
        if (documented.isBlank() || documented.equals("—")) {
            return;
        }
        final String question = "default of `" + row.key() + "` matches `@ConfigProperty(defaultValue = …)`";
        if (!c.defaultIsLiteral()) {
            findings.add(defaultFinding(
                    doc,
                    s,
                    owner,
                    row,
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    question,
                    "`defaultValue` of `" + row.key() + "` in `" + owner.className()
                            + "` is not a plain string literal; documented default `" + documented
                            + "` is unverifiable."));
            return;
        }
        if (!normalizeDefault(documented).equals(normalizeDefault(c.defaultValue()))) {
            findings.add(defaultFinding(
                    doc,
                    s,
                    owner,
                    row,
                    Outcome.ABSENT,
                    Lane.ASSERT,
                    question,
                    "Documented default `" + documented + "` of `" + row.key() + "` no longer matches "
                            + "`@ConfigProperty(defaultValue = \"" + c.defaultValue() + "\")` in `" + owner.className()
                            + "` (`" + owner.path() + "`)."));
        }
    }

    /**
     * Resolves the config record a section documents: the cited {@code Source:} path when it exists and
     * declares the class; else the unique indexed file of the same basename declaring the class with a
     * config prefix; else — a certain rename — the unique {@code @ConfigData} owner of the section's
     * prefix that declares every documented key, which additionally asserts a MOVED finding carrying the
     * resolved path for {@code --fix}.
     *
     * @param doc      the tunables catalog document.
     * @param s        the section to resolve.
     * @param findings the list to append the prefix-move finding to, when one is emitted.
     * @return the resolved record, or {@code null} when the section cannot be resolved with certainty
     *     (the generic source-path anchor reports the gone citation).
     */
    private ConfigOwner resolveRecord(final KbDocument doc, final Section s, final List<Finding> findings) {
        if (s.sourcePath() != null && index.fileExists(s.sourcePath())) {
            final TypeInfo type = index.parse(s.sourcePath()).types().get(s.className());
            return type == null ? null : new ConfigOwner(s.sourcePath(), s.className(), type);
        }
        final List<ConfigOwner> byBasename = new ArrayList<>();
        for (final String p : index.pathsForBasename(s.className() + ".java")) {
            final TypeInfo type = index.parse(p).types().get(s.className());
            if (type != null && type.configPrefix() != null) {
                byBasename.add(new ConfigOwner(p, s.className(), type));
            }
        }
        if (byBasename.size() == 1) {
            // The generic source-path anchor already reports this as a package/path move with a ready
            // rewrite; here it only needs to back the per-key checks.
            return byBasename.get(0);
        }
        if (!byBasename.isEmpty()) {
            return null;
        }
        final List<ConfigOwner> owners = ownersOf(s.prefix());
        if (owners.size() == 1 && declaresAllDocumentedKeys(owners.get(0), s)) {
            final ConfigOwner owner = owners.get(0);
            findings.add(prefixFinding(
                    doc,
                    s,
                    Outcome.PRESENT,
                    Lane.ASSERT,
                    "Cited config class `" + s.className() + ".java` is gone, but `@ConfigData(\"" + s.prefix()
                            + "\")` is declared by exactly one indexed record — `" + owner.className() + "` at `"
                            + owner.path() + "` — which declares every key this section documents "
                            + "(a config class rename/move). Update the heading, `Source:` link, and `Module:` label.",
                    owner.path()));
            return owner;
        }
        return null;
    }

    /**
     * Whether a prefix owner declares every key a section documents (by property name under the
     * section's prefix). The guard that keeps prefix-based resolution certain: several records may
     * plausibly own a prefix over time, but only the true successor carries all the documented keys.
     *
     * @param owner the candidate record.
     * @param s     the section whose rows must all resolve.
     * @return {@code true} when every documented key is declared.
     */
    private static boolean declaresAllDocumentedKeys(final ConfigOwner owner, final Section s) {
        final String fqPrefix = s.prefix().isEmpty() ? "" : s.prefix() + ".";
        final List<String> declared = new ArrayList<>();
        for (final ConfigComponent c : owner.type().configComponents()) {
            declared.add(c.keyName());
        }
        for (final Row row : s.rows()) {
            if (!row.key().startsWith(fqPrefix)) {
                return false;
            }
            if (!declared.contains(row.key().substring(fqPrefix.length()))) {
                return false;
            }
        }
        return !s.rows().isEmpty();
    }

    /**
     * The indexed config records declaring {@code @ConfigData} with the given prefix, from a lazily-built
     * scan of every indexed {@code *Config.java} (the repo's config-record naming convention).
     *
     * @param prefix the config prefix to look up ({@code ""} for bare {@code @ConfigData}).
     * @return the owners of the prefix, in deterministic (path-sorted) order; possibly empty.
     */
    private List<ConfigOwner> ownersOf(final String prefix) {
        if (ownersByPrefix == null) {
            ownersByPrefix = new LinkedHashMap<>();
            for (final String basename : index.basenames()) {
                if (!basename.endsWith("Config.java")) {
                    continue;
                }
                for (final String path : index.pathsForBasename(basename)) {
                    for (final Map.Entry<String, TypeInfo> e :
                            index.parse(path).types().entrySet()) {
                        final String p = e.getValue().configPrefix();
                        if (p != null) {
                            ownersByPrefix
                                    .computeIfAbsent(p, k -> new ArrayList<>())
                                    .add(new ConfigOwner(path, e.getKey(), e.getValue()));
                        }
                    }
                }
            }
        }
        return ownersByPrefix.getOrDefault(prefix, List.of());
    }

    // ---- Finding builders ----

    /**
     * Builds a section-level {@link AnchorKind#CONFIG_PREFIX} finding, keyed on the cited source path so
     * a move rewrite can ride the shared auto-fix machinery.
     *
     * @param doc          the tunables catalog document.
     * @param s            the section.
     * @param outcome      the outcome.
     * @param lane         the lane.
     * @param evidence     the one-look justification.
     * @param resolvedPath the moved-to path (drives the auto-fix rewrite), or {@code null}.
     * @return the finding.
     */
    private static Finding prefixFinding(
            final KbDocument doc,
            final Section s,
            final Outcome outcome,
            final Lane lane,
            final String evidence,
            final String resolvedPath) {
        final String target = s.sourcePath() != null ? s.sourcePath() : s.className() + ".java";
        final List<Occurrence> occurrences = new ArrayList<>();
        occurrences.add(new Occurrence(s.headingLine(), Anchor.NO_LINE, s.className()));
        if (s.sourceLine() > 0) {
            occurrences.add(new Occurrence(s.sourceLine(), Anchor.NO_LINE, target));
        }
        return new Finding(
                Hashing.id(doc.entry().key(), target, AnchorKind.CONFIG_PREFIX.name()),
                doc.entry().key(),
                doc.entry().relativePath(),
                doc.entry().type(),
                AnchorKind.CONFIG_PREFIX,
                target,
                moduleOf(target),
                s.className(),
                outcome,
                lane,
                "config prefix `" + headingPrefix(s) + "` is declared by cited class `" + s.className() + "`",
                evidence,
                occurrences,
                null,
                resolvedPath,
                s.moduleLabel());
    }

    /**
     * Builds a per-row {@link AnchorKind#CONFIG_KEY} finding.
     *
     * @param doc      the tunables catalog document.
     * @param s        the section.
     * @param owner    the resolved config record.
     * @param row      the documented row.
     * @param outcome  the outcome.
     * @param lane     the lane.
     * @param evidence the one-look justification.
     * @return the finding.
     */
    private static Finding keyFinding(
            final KbDocument doc,
            final Section s,
            final ConfigOwner owner,
            final Row row,
            final Outcome outcome,
            final Lane lane,
            final String evidence) {
        return new Finding(
                Hashing.id(doc.entry().key(), row.key(), AnchorKind.CONFIG_KEY.name()),
                doc.entry().key(),
                doc.entry().relativePath(),
                doc.entry().type(),
                AnchorKind.CONFIG_KEY,
                row.key(),
                moduleOf(owner.path()),
                owner.className(),
                outcome,
                lane,
                "config record `" + owner.className() + "` declares property `" + row.key() + "`",
                evidence,
                List.of(new Occurrence(row.line(), Anchor.NO_LINE, row.key())),
                null,
                null,
                null);
    }

    /**
     * Builds a per-row {@link AnchorKind#CONFIG_DEFAULT} finding.
     *
     * @param doc      the tunables catalog document.
     * @param s        the section.
     * @param owner    the resolved config record.
     * @param row      the documented row.
     * @param outcome  the outcome.
     * @param lane     the lane.
     * @param question the exact question asked.
     * @param evidence the one-look justification.
     * @return the finding.
     */
    private static Finding defaultFinding(
            final KbDocument doc,
            final Section s,
            final ConfigOwner owner,
            final Row row,
            final Outcome outcome,
            final Lane lane,
            final String question,
            final String evidence) {
        return new Finding(
                Hashing.id(doc.entry().key(), row.key(), AnchorKind.CONFIG_DEFAULT.name()),
                doc.entry().key(),
                doc.entry().relativePath(),
                doc.entry().type(),
                AnchorKind.CONFIG_DEFAULT,
                row.key(),
                moduleOf(owner.path()),
                owner.className(),
                outcome,
                lane,
                question,
                evidence,
                List.of(new Occurrence(row.line(), Anchor.NO_LINE, row.key())),
                null,
                null,
                null);
    }

    // ---- Helpers ----

    /**
     * The section prefix as the heading displays it ({@code p.*}, or the class name for a no-prefix
     * section).
     *
     * @param s the section.
     * @return the display prefix.
     */
    private static String headingPrefix(final Section s) {
        return s.prefix().isEmpty() ? "(no prefix)" : s.prefix() + ".*";
    }

    /**
     * Whether a documented type matches the declared component type, comparing simple names
     * case-insensitively (the catalog writes {@code boolean}/{@code Duration} etc. as the code does,
     * but package qualifiers and wrapper-vs-primitive casing are not drift).
     *
     * @param documented the documented type cell.
     * @param declared   the as-written component type.
     * @return {@code true} when the types match.
     */
    private static boolean typeMatches(final String documented, final String declared) {
        return simpleTypeName(documented).equals(simpleTypeName(declared));
    }

    /**
     * The lowercased simple name of a possibly-qualified type, generics stripped.
     *
     * @param type the type text.
     * @return the comparison form of the type name.
     */
    private static String simpleTypeName(final String type) {
        String t = type.strip().replaceAll("\\s+", "");
        final int lt = t.indexOf('<');
        if (lt > 0) {
            t = t.substring(0, lt);
        }
        final int dot = t.lastIndexOf('.');
        return (dot >= 0 ? t.substring(dot + 1) : t).toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes a default literal for comparison: trimmed, whitespace runs collapsed, and the
     * catalog's empty-string spellings ({@code ""} and {@code (empty)}) mapped to the empty string.
     *
     * @param s the default text.
     * @return the normalized value.
     */
    private static String normalizeDefault(final String s) {
        final String t = s.strip().replaceAll("\\s+", " ");
        return t.equals("\"\"") || t.equals("(empty)") ? "" : t;
    }

    /**
     * The module directory of a repo-relative path (the segment preceding {@code src}).
     *
     * @param repoRelPath the repo-relative path.
     * @return the module name, or {@code null} if the path has no {@code src} segment.
     */
    private static String moduleOf(final String repoRelPath) {
        final String[] parts = repoRelPath.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("src")) {
                return parts[i - 1];
            }
        }
        return null;
    }
}
