// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.consensus.kbfreshness.model.Entry;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.util.Patterns;

/**
 * Walks the consensus-layer KB directory and produces one {@link KbDocument} per drift-checkable
 * entry. Entry type is derived from the path per the KB {@code LAYOUT.md} type vocabulary; {@code README}
 * index files are scanned as {@link EntryType#INDEX} (their rows carry a sync obligation), while
 * convention scaffolding ({@code FORMAT}/{@code LAYOUT}/{@code CLAUDE} — placeholder examples by design)
 * is skipped. Output is sorted by path for determinism.
 */
public final class KbScanner {

    /** Matches a per-file catalog ID prefix (one that gets its own markdown file) at the start of a file name. */
    private static final Pattern CATALOG_ID = Pattern.compile("^(" + Patterns.FILE_CATALOG_PREFIXES + ")-(\\d{3})");

    /** Repository root (absolute, normalized), used to compute display paths. */
    private final Path repoRoot;

    /** Consensus-layer KB root (absolute, normalized) to walk. */
    private final Path kbRoot;

    /**
     * Creates a scanner bound to a repository and its KB root.
     *
     * @param repoRoot repository root, used to compute display paths.
     * @param kbRoot   the consensus-layer KB root (e.g. {@code <repo>/platform-sdk/docs/consensus-layer}).
     */
    public KbScanner(final Path repoRoot, final Path kbRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.kbRoot = kbRoot.toAbsolutePath().normalize();
    }

    /**
     * Walks the KB root, parses every drift-checkable {@code .md} file, and returns the resulting
     * documents sorted by relative path.
     *
     * @return the scanned documents, one per non-scaffolding entry, ordered by path.
     * @throws UncheckedIOException if walking or reading the KB fails.
     */
    public List<KbDocument> scan() {
        final List<KbDocument> docs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(kbRoot)) {
            final List<Path> mdFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (final Path p : mdFiles) {
                final Path relToKb = kbRoot.relativize(p);
                final EntryType type = classify(relToKb);
                if (type == EntryType.OTHER) {
                    continue;
                }
                final List<String> lines = Files.readAllLines(p);
                final Frontmatter fm = FrontmatterParser.parse(lines);
                final String key = keyFor(type, relToKb, fm);
                final String displayPath = repoRoot.relativize(p).toString();
                final Entry entry = new Entry(key, displayPath, type, fm.scalar("last_reviewed"));
                docs.add(new KbDocument(entry, lines, fm));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to scan KB at " + kbRoot, e);
        }
        docs.sort(Comparator.comparing(d -> d.entry().relativePath()));
        return docs;
    }

    /**
     * Classifies a KB-relative path into its document type, or {@link EntryType#OTHER} to skip.
     *
     * @param relToKb the path relative to the KB root.
     * @return the derived entry type, or {@link EntryType#OTHER} for scaffolding/unrecognized paths.
     */
    static EntryType classify(final Path relToKb) {
        final String rel = relToKb.toString().replace('\\', '/');
        final String name = relToKb.getFileName().toString();
        if (name.equals("README.md")) {
            // Index files carry entry links and catalog IDs with a sync obligation — drift-checked.
            return EntryType.INDEX;
        }
        if (name.equals("FORMAT.md") || name.equals("LAYOUT.md") || name.equals("CLAUDE.md")) {
            return EntryType.OTHER;
        }
        if (rel.equals("glossary.md")) {
            return EntryType.GLOSSARY;
        }
        if (rel.equals("symptoms.md")) {
            return EntryType.SYMPTOM_CATALOG;
        }
        if (rel.equals("tunables.md")) {
            return EntryType.TUNABLE_CATALOG;
        }
        if (rel.equals("architecture/overview.md")) {
            return EntryType.ARCHITECTURE_OVERVIEW;
        }
        if (rel.startsWith("architecture/interfaces/")) {
            return EntryType.ARCHITECTURE_INTERFACE;
        }
        if (rel.startsWith("architecture/topics/")) {
            return EntryType.ARCHITECTURE_TOPIC;
        }
        if (rel.startsWith("concepts/")) {
            return EntryType.CONCEPT;
        }
        if (rel.startsWith("decisions/") && name.startsWith("ADR-")) {
            return EntryType.DECISION;
        }
        if (rel.startsWith("invariants/") && name.startsWith("INV-")) {
            return EntryType.INVARIANT;
        }
        if (rel.startsWith("rules/") && name.startsWith("RUL-")) {
            return EntryType.RULE;
        }
        if (rel.startsWith("scenarios/") && name.startsWith("SCN-")) {
            return EntryType.SCENARIO;
        }
        if (rel.startsWith("heuristics/") && name.startsWith("HEU-")) {
            return EntryType.HEURISTIC;
        }
        if (rel.startsWith("delta-map/")) {
            return EntryType.DELTA_MAP;
        }
        return EntryType.OTHER;
    }

    /**
     * The stable entry key: the catalog ID for catalog entries, otherwise a type-namespaced slug so
     * a topic and its delta-map counterpart never collide.
     *
     * @param type    the entry's type.
     * @param relToKb the path relative to the KB root.
     * @param fm      the parsed frontmatter, consulted for an explicit {@code id}.
     * @return the stable, collision-free entry key.
     */
    static String keyFor(final EntryType type, final Path relToKb, final Frontmatter fm) {
        final String name = relToKb.getFileName().toString();
        final String slug = name.substring(0, name.length() - ".md".length());
        return switch (type) {
            case DECISION, INVARIANT, RULE, SCENARIO, HEURISTIC -> {
                final String id = fm.scalar("id");
                if (id != null && !id.isBlank()) {
                    yield id.strip();
                }
                final var m = CATALOG_ID.matcher(name);
                yield m.find() ? m.group() : slug;
            }
            case ARCHITECTURE_TOPIC -> "topic:" + slug;
            case ARCHITECTURE_INTERFACE -> "interface:" + slug;
            case ARCHITECTURE_OVERVIEW -> "architecture-overview";
            case INDEX -> {
                final Path parent = relToKb.getParent();
                yield parent == null ? "index" : "index:" + parent.toString().replace('\\', '/');
            }
            case CONCEPT -> "concept:" + slug;
            case DELTA_MAP -> "delta-map:" + slug;
            case GLOSSARY -> "glossary";
            case SYMPTOM_CATALOG -> "symptoms";
            case TUNABLE_CATALOG -> "tunables";
            case OTHER -> slug.toLowerCase(Locale.ROOT);
        };
    }
}
