// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.hiero.consensus.kbfreshness.resolve.ConfigRecords;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ConfigComponent;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.Default;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.TypeInfo;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * Tier-1/2 tunables-catalog checks. The catalog's column conventions make each row mechanically checkable
 * against the {@code @ConfigData} record it documents: a documented key must be a declared
 * {@code @ConfigProperty} (Tier 1), and a documented plain-literal default must match the
 * {@code defaultValue} literal (Tier 2). Per the precision mandate only those two literal mismatches
 * assert; a type difference, a non-literal default, an undocumented property, and an undocumented record
 * are routed to the quiet log or coverage lane (see the module {@code CLAUDE.md} for the full routing).
 * A section whose cited source is gone is additionally resolved <em>by config prefix</em>: exactly one
 * indexed record declaring the section's prefix and every documented key is a certain rename — asserted
 * with the resolved path so {@code --fix} can rewrite the heading, {@code Source:} link, and label.
 */
public final class TunablesDiffAssembler {

    /**
     * Module-name prefix that puts a config record in scope for the undocumented-record coverage check
     * regardless of what the catalog already documents. The KB is the consensus layer's, so every
     * {@code consensus-*} module's tunables belong in its catalog; other modules are in scope only once
     * the catalog documents them (which keeps e.g. hedera-node and storage-engine records out of the lane).
     */
    private static final String IN_SCOPE_MODULE_PREFIX = "consensus-";

    /**
     * Well-known config-API constants accepted as {@code defaultValue}: as-written reference to its
     * string value. A closed whitelist — each value is a compile-time constant of
     * {@code com.swirlds.config.api.Configuration}, so comparing it is comparing a literal, not guessing.
     */
    private static final Map<String, String> WELL_KNOWN_DEFAULTS = Map.of(
            "Configuration.EMPTY_LIST", "[]",
            "com.swirlds.config.api.Configuration.EMPTY_LIST", "[]");

    /** Source index used to resolve and parse the documented config records. */
    private final SourceIndex index;

    /** Lazily-built scan of every indexed config record (see {@link ConfigRecords}). */
    private List<ConfigRecords.Owner> allOwners;

    /** Lazily-built map of {@code @ConfigData} prefix to the indexed records declaring it. */
    private Map<String, List<ConfigRecords.Owner>> ownersByPrefix;

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
                final List<Section> sections = TunablesCatalog.parse(doc);
                final Set<String> documentedRecords = new LinkedHashSet<>();
                final Set<String> documentedModules = new TreeSet<>();
                for (final Section s : sections) {
                    findings.addAll(checkSection(doc, s, documentedRecords, documentedModules));
                }
                findings.addAll(undocumentedRecords(doc, sections, documentedRecords, documentedModules));
            }
        }
        return findings;
    }

    /**
     * Checks one catalog section: resolves its config record (by cited path, unique basename, or unique
     * prefix owner) and diffs the documented rows against the record's declared properties. The resolved
     * record and its module are recorded so the undocumented-record coverage check knows what the
     * catalog already covers.
     *
     * @param doc               the tunables catalog document.
     * @param s                 the section to check.
     * @param documentedRecords set collecting {@code path|className} of every resolved record.
     * @param documentedModules set collecting the modules the catalog documents (resolved and labeled).
     * @return the section's findings (possibly empty).
     */
    private List<Finding> checkSection(
            final KbDocument doc,
            final Section s,
            final Set<String> documentedRecords,
            final Set<String> documentedModules) {
        final List<Finding> findings = new ArrayList<>();
        if (s.moduleLabel() != null) {
            documentedModules.add(s.moduleLabel());
        }
        final RecordResolution resolved = resolveRecord(doc, s);
        if (resolved.moveFinding() != null) {
            findings.add(resolved.moveFinding());
        }
        final ConfigRecords.Owner owner = resolved.owner();
        if (owner == null) {
            return findings;
        }
        documentedRecords.add(owner.path() + "|" + owner.className());
        if (owner.module() != null) {
            documentedModules.add(owner.module());
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
        findings.addAll(checkRows(doc, s, owner));
        return findings;
    }

    /**
     * Diffs a section's rows against the resolved record's declared properties: gone keys assert,
     * default mismatches assert (literal vs literal only), type differences and non-literal defaults go
     * to the quiet log, and undocumented properties land in the coverage lane.
     *
     * @param doc   the tunables catalog document.
     * @param s     the section being checked.
     * @param owner the resolved config record.
     * @return the section's row-level findings, in report order.
     */
    private static List<Finding> checkRows(final KbDocument doc, final Section s, final ConfigRecords.Owner owner) {
        final String fqPrefix = s.prefix().isEmpty() ? "" : s.prefix() + ".";
        final Map<String, ConfigComponent> byKeyName = new LinkedHashMap<>();
        for (final ConfigComponent c : owner.type().configComponents()) {
            byKeyName.putIfAbsent(c.keyName(), c);
        }
        final List<Finding> findings = new ArrayList<>();
        final List<String> documentedNames = new ArrayList<>();
        for (final Row row : s.rows()) {
            findings.addAll(checkRow(doc, s, owner, row, fqPrefix, byKeyName, documentedNames));
        }
        findings.addAll(undocumentedProperties(doc, s, owner, fqPrefix, documentedNames));
        return findings;
    }

    /**
     * Checks one documented row against the resolved record, recording the property name it documents in
     * {@code documentedNames} (so {@link #undocumentedProperties} can find the gaps): an off-prefix key is
     * unverifiable, a gone key asserts, a differing type is quiet-logged, and the default is diffed.
     *
     * @param doc             the tunables catalog document.
     * @param s               the section being checked.
     * @param owner           the resolved config record.
     * @param row             the documented row.
     * @param fqPrefix        the section's fully-qualified key prefix ({@code ""} for a no-prefix section).
     * @param byKeyName       the record's declared components by property name.
     * @param documentedNames collects the property name this row documents (mutated).
     * @return the row's findings, in report order.
     */
    private static List<Finding> checkRow(
            final KbDocument doc,
            final Section s,
            final ConfigRecords.Owner owner,
            final Row row,
            final String fqPrefix,
            final Map<String, ConfigComponent> byKeyName,
            final List<String> documentedNames) {
        if (!fqPrefix.isEmpty() && !row.key().startsWith(fqPrefix)) {
            return List.of(keyFinding(
                    doc,
                    owner,
                    row,
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    "Row key `" + row.key() + "` does not start with the section prefix `" + fqPrefix
                            + "`; not checked."));
        }
        final String propName = fqPrefix.isEmpty() ? row.key() : row.key().substring(fqPrefix.length());
        documentedNames.add(propName);
        final ConfigComponent c = byKeyName.get(propName);
        if (c == null) {
            return List.of(keyFinding(
                    doc,
                    owner,
                    row,
                    Outcome.ABSENT,
                    Lane.ASSERT,
                    "Config record `" + owner.className() + "` (`@ConfigData(\"" + s.prefix() + "\")`) in `"
                            + owner.path() + "` declares no property `" + propName + "`; declared: "
                            + String.join(", ", byKeyName.keySet()) + "."));
        }
        final List<Finding> findings = new ArrayList<>();
        if (!row.type().isBlank() && !typeMatches(row.type(), c.type())) {
            findings.add(keyFinding(
                    doc,
                    owner,
                    row,
                    Outcome.PRESENT,
                    Lane.QUIET_LOG,
                    "Documented type `" + row.type() + "` of `" + row.key() + "` differs from the declared `"
                            + c.type() + "` in `" + owner.className()
                            + "` — possibly a semantic type (not asserted)."));
        }
        findings.addAll(checkDefault(doc, owner, row, c));
        return findings;
    }

    /**
     * The inverse of the per-row diff: declared properties the section documents no row for, surfaced in
     * the coverage lane (never asserted).
     *
     * @param doc             the tunables catalog document.
     * @param s               the section being checked.
     * @param owner           the resolved config record.
     * @param fqPrefix        the section's fully-qualified key prefix.
     * @param documentedNames the property names the section's rows documented.
     * @return one coverage finding per undocumented declared property.
     */
    private static List<Finding> undocumentedProperties(
            final KbDocument doc,
            final Section s,
            final ConfigRecords.Owner owner,
            final String fqPrefix,
            final List<String> documentedNames) {
        final List<Finding> findings = new ArrayList<>();
        for (final ConfigComponent c : owner.type().configComponents()) {
            if (!documentedNames.contains(c.keyName())) {
                final String fqKey = fqPrefix + c.keyName();
                findings.add(Finding.of(
                        doc.entry(),
                        AnchorKind.CONFIG_KEY,
                        fqKey,
                        RepoPaths.moduleOf(owner.path()),
                        owner.className(),
                        Outcome.PRESENT,
                        Lane.COVERAGE_GAP,
                        "config record `" + owner.className() + "` property `" + c.keyName() + "` is documented",
                        "Config record `" + owner.className() + "` declares property `" + c.keyName() + "` (key `"
                                + fqKey + "`) not documented in this section.",
                        List.of(new Occurrence(s.headingLine(), Anchor.NO_LINE, fqKey))));
            }
        }
        return findings;
    }

    /**
     * Compares a row's documented default against the component's effective literal {@code defaultValue}:
     * equal literals are clean, differing literals assert, and a non-literal source value is unverifiable.
     * A blank documented default is skipped — nothing is claimed.
     *
     * @param doc   the tunables catalog document.
     * @param owner the resolved config record.
     * @param row   the documented row.
     * @param c     the declared component the row documents.
     * @return the mismatch finding (assert or quiet-log), or an empty list when the default is clean or blank.
     */
    private static List<Finding> checkDefault(
            final KbDocument doc, final ConfigRecords.Owner owner, final Row row, final ConfigComponent c) {
        final String documented = row.defaultValue();
        if (documented.isBlank() || documented.equals("—")) {
            return List.of();
        }
        final String question = "default of `" + row.key() + "` matches `@ConfigProperty(defaultValue = …)`";
        // Reduce a plain literal or a whitelisted config-API constant (a compile-time fact) to one
        // effective literal with its mismatch wording; a non-literal, non-whitelisted, or absent default
        // yields null (unverifiable).
        final EffectiveDefault effective =
                switch (c.defaultSpec()) {
                    case Default.Literal(String value) ->
                        new EffectiveDefault(
                                value,
                                "Documented default `" + documented + "` of `" + row.key() + "` no longer matches "
                                        + "`@ConfigProperty(defaultValue = \"" + value + "\")` in `" + owner.className()
                                        + "` (`" + owner.path() + "`).");
                    case Default.Expr(String expression) -> {
                        final String known = WELL_KNOWN_DEFAULTS.get(expression);
                        yield known == null
                                ? null
                                : new EffectiveDefault(
                                        known,
                                        "Documented default `" + documented + "` of `" + row.key()
                                                + "` no longer matches `@ConfigProperty(defaultValue = " + expression
                                                + ")` (= `" + known + "`) in `" + owner.className() + "` (`"
                                                + owner.path() + "`).");
                    }
                    case Default.None() -> null;
                };
        if (effective == null) {
            return List.of(defaultFinding(
                    doc,
                    owner,
                    row,
                    Outcome.UNVERIFIABLE,
                    Lane.QUIET_LOG,
                    question,
                    "`defaultValue` of `" + row.key() + "` in `" + owner.className()
                            + "` is not a plain string literal; documented default `" + documented
                            + "` is unverifiable."));
        }
        if (!normalizeDefault(documented).equals(normalizeDefault(effective.value()))) {
            return List.of(defaultFinding(
                    doc, owner, row, Outcome.ABSENT, Lane.ASSERT, question, effective.mismatchEvidence()));
        }
        return List.of();
    }

    /**
     * A component's {@code defaultValue} reduced to a single comparable literal (a plain literal, or a
     * whitelisted constant's compile-time value) together with the evidence to render when the documented
     * default no longer matches it.
     *
     * @param value            the effective literal to compare the documented default against.
     * @param mismatchEvidence the one-look evidence for a mismatch assertion.
     */
    private record EffectiveDefault(String value, String mismatchEvidence) {}

    /**
     * The outcome of resolving a section's config record: the resolved {@code owner} (or {@code null} when
     * the section cannot be resolved with certainty) plus the prefix-move assert {@code moveFinding} when a
     * config-class rename was detected (otherwise {@code null}).
     *
     * @param owner       the resolved config record, or {@code null}.
     * @param moveFinding the prefix-move finding to emit, or {@code null}.
     */
    private record RecordResolution(ConfigRecords.Owner owner, Finding moveFinding) {

        /** A resolution that found no certain record and emits no finding. */
        private static RecordResolution unresolved() {
            return new RecordResolution(null, null);
        }
    }

    /**
     * Resolves the config record a section documents: the cited {@code Source:} path when it declares the
     * class; else the unique indexed basename declaring it; else — a certain rename — the unique
     * {@code @ConfigData} owner of the section's prefix that declares every documented key (carrying a
     * MOVED finding with the resolved path for {@code --fix}).
     *
     * @param doc the tunables catalog document.
     * @param s   the section to resolve.
     * @return the resolution: the resolved record (or {@code null} when none is certain) plus the
     *     prefix-move finding when a rename was detected.
     */
    private RecordResolution resolveRecord(final KbDocument doc, final Section s) {
        if (s.sourcePath() != null && index.fileExists(s.sourcePath())) {
            final TypeInfo type = index.parse(s.sourcePath()).types().get(s.className());
            return new RecordResolution(
                    type == null ? null : new ConfigRecords.Owner(s.sourcePath(), s.className(), type), null);
        }
        final List<ConfigRecords.Owner> byBasename = new ArrayList<>();
        for (final String p : index.pathsForBasename(s.className() + ".java")) {
            final TypeInfo type = index.parse(p).types().get(s.className());
            if (type != null && type.configPrefix() != null) {
                byBasename.add(new ConfigRecords.Owner(p, s.className(), type));
            }
        }
        if (byBasename.size() == 1) {
            // The generic source-path anchor already reports this as a package/path move with a ready
            // rewrite; here it only needs to back the per-key checks.
            return new RecordResolution(byBasename.get(0), null);
        }
        if (!byBasename.isEmpty()) {
            return RecordResolution.unresolved();
        }
        final List<ConfigRecords.Owner> owners = ownersOf(s.prefix());
        if (owners.size() == 1 && declaresAllDocumentedKeys(owners.get(0), s)) {
            final ConfigRecords.Owner owner = owners.get(0);
            final Finding move = prefixFinding(
                    doc,
                    s,
                    Outcome.PRESENT,
                    Lane.ASSERT,
                    "Cited config class `" + s.className() + ".java` is gone, but `@ConfigData(\"" + s.prefix()
                            + "\")` is declared by exactly one indexed record — `" + owner.className() + "` at `"
                            + owner.path() + "` — which declares every key this section documents "
                            + "(a config class rename/move). Update the heading, `Source:` link, and `Module:` label.",
                    owner.path());
            return new RecordResolution(owner, move);
        }
        return RecordResolution.unresolved();
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
    private static boolean declaresAllDocumentedKeys(final ConfigRecords.Owner owner, final Section s) {
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
     * The indexed config records declaring {@code @ConfigData} with the given prefix, from the lazily-run
     * {@link ConfigRecords} scan.
     *
     * @param prefix the config prefix to look up ({@code ""} for bare {@code @ConfigData}).
     * @return the owners of the prefix, in deterministic (basename, path) order; possibly empty.
     */
    private List<ConfigRecords.Owner> ownersOf(final String prefix) {
        if (ownersByPrefix == null) {
            ownersByPrefix = new LinkedHashMap<>();
            for (final ConfigRecords.Owner owner : allOwners()) {
                ownersByPrefix
                        .computeIfAbsent(owner.type().configPrefix(), k -> new ArrayList<>())
                        .add(owner);
            }
        }
        return ownersByPrefix.getOrDefault(prefix, List.of());
    }

    /**
     * Every indexed config record, scanned once per run.
     *
     * @return the records in deterministic (basename, path) order.
     */
    private List<ConfigRecords.Owner> allOwners() {
        if (allOwners == null) {
            allOwners = ConfigRecords.scan(index);
        }
        return allOwners;
    }

    /**
     * The inverse of the per-section checks: in-scope config records the catalog has no section for at
     * all. A record is in scope when its module is a {@code consensus-*} module (the layer this KB
     * documents) or a module the catalog already documents. A record is "documented" when a section
     * resolved to it, or names its class or its prefix — a stale-but-present section is drift, not a
     * coverage gap, and must not be double-reported here. Never asserted: coverage lane only.
     *
     * @param doc               the tunables catalog document.
     * @param sections          the catalog's parsed sections.
     * @param documentedRecords {@code path|className} of every record a section resolved to.
     * @param documentedModules the modules the catalog documents.
     * @return one coverage finding per undocumented in-scope record.
     */
    private List<Finding> undocumentedRecords(
            final KbDocument doc,
            final List<Section> sections,
            final Set<String> documentedRecords,
            final Set<String> documentedModules) {
        final Set<String> sectionClassNames = new TreeSet<>();
        final Set<String> sectionPrefixes = new TreeSet<>();
        for (final Section s : sections) {
            sectionClassNames.add(s.className());
            sectionPrefixes.add(s.prefix());
        }
        final List<Finding> findings = new ArrayList<>();
        for (final ConfigRecords.Owner owner : allOwners()) {
            final String module = owner.module();
            final boolean inScope =
                    module != null && (module.startsWith(IN_SCOPE_MODULE_PREFIX) || documentedModules.contains(module));
            if (!inScope
                    || documentedRecords.contains(owner.path() + "|" + owner.className())
                    || sectionClassNames.contains(owner.className())
                    || sectionPrefixes.contains(owner.type().configPrefix())) {
                continue;
            }
            final String prefix = owner.type().configPrefix();
            findings.add(Finding.of(
                    doc.entry(),
                    AnchorKind.CONFIG_PREFIX,
                    owner.path(),
                    module,
                    owner.className(),
                    Outcome.PRESENT,
                    Lane.COVERAGE_GAP,
                    "config record `" + owner.className() + "` has a section in this catalog",
                    "Config record `" + owner.className() + "` (`@ConfigData(\""
                            + prefix + "\")`, "
                            + owner.type().configComponents().size() + " key(s)) at `"
                            + owner.path() + "` has no section in this catalog.",
                    List.of(new Occurrence(1, Anchor.NO_LINE, owner.className()))));
        }
        return findings;
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
        return Finding.of(
                        doc.entry(),
                        AnchorKind.CONFIG_PREFIX,
                        target,
                        RepoPaths.moduleOf(target),
                        s.className(),
                        outcome,
                        lane,
                        "config prefix `" + headingPrefix(s) + "` is declared by cited class `" + s.className() + "`",
                        evidence,
                        occurrences)
                .withResolvedPath(resolvedPath)
                .withStatedModule(s.moduleLabel());
    }

    /**
     * Builds a per-row {@link AnchorKind#CONFIG_KEY} finding.
     *
     * @param doc      the tunables catalog document.
     * @param owner    the resolved config record.
     * @param row      the documented row.
     * @param outcome  the outcome.
     * @param lane     the lane.
     * @param evidence the one-look justification.
     * @return the finding.
     */
    private static Finding keyFinding(
            final KbDocument doc,
            final ConfigRecords.Owner owner,
            final Row row,
            final Outcome outcome,
            final Lane lane,
            final String evidence) {
        return Finding.of(
                doc.entry(),
                AnchorKind.CONFIG_KEY,
                row.key(),
                RepoPaths.moduleOf(owner.path()),
                owner.className(),
                outcome,
                lane,
                "config record `" + owner.className() + "` declares property `" + row.key() + "`",
                evidence,
                List.of(new Occurrence(row.line(), Anchor.NO_LINE, row.key())));
    }

    /**
     * Builds a per-row {@link AnchorKind#CONFIG_DEFAULT} finding.
     *
     * @param doc      the tunables catalog document.
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
            final ConfigRecords.Owner owner,
            final Row row,
            final Outcome outcome,
            final Lane lane,
            final String question,
            final String evidence) {
        return Finding.of(
                doc.entry(),
                AnchorKind.CONFIG_DEFAULT,
                row.key(),
                RepoPaths.moduleOf(owner.path()),
                owner.className(),
                outcome,
                lane,
                question,
                evidence,
                List.of(new Occurrence(row.line(), Anchor.NO_LINE, row.key())));
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
     * Normalizes a default literal for comparison: trimmed, whitespace runs collapsed, and the empty
     * spellings mapped to the empty string — the catalog's {@code ""} and {@code (empty)}, plus the
     * config API's empty-list value {@code []} ({@code Configuration.EMPTY_LIST}), so a documented
     * {@code (empty)} matches an empty-list default.
     *
     * @param s the default text.
     * @return the normalized value.
     */
    private static String normalizeDefault(final String s) {
        final String t = s.strip().replaceAll("\\s+", " ");
        return t.equals("\"\"") || t.equals("(empty)") || t.equals("[]") ? "" : t;
    }
}
