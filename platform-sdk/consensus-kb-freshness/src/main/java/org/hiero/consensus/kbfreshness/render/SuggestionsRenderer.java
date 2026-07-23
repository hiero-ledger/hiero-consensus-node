// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.findings.NearNameMatcher;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.ConfigRecords;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * Renders non-asserting "did you mean" hints for gone targets (a cited doc, source path, bare basename,
 * or config key that no longer resolves): a definite git rename or a near-name match scored by
 * {@link NearNameMatcher}, made actionable where unambiguous. A suggestion, never a fact — it respects
 * the "never assert" invariant and is kept out of the machine artifact. Formatting-only; all scoring
 * lives in {@link NearNameMatcher}. Deterministic for a given checkout.
 */
public final class SuggestionsRenderer {

    /** Maximum number of near-name suggestions offered per gone target. */
    private static final int MAX_SUGGESTIONS = 3;

    /** Prevents instantiation of this static-only renderer. */
    private SuggestionsRenderer() {}

    /**
     * Renders the did-you-mean suggestions as Markdown.
     *
     * @param result the run result.
     * @param git    the git wrapper for rename detection (may report unavailable).
     * @return the rendered Markdown suggestions.
     */
    public static String render(final RunResult result, final Git git) {
        final List<String> docPaths = new ArrayList<>();
        final Map<String, String> titleByPath = new HashMap<>();
        for (final var d : result.documents()) {
            docPaths.add(d.entry().relativePath());
            final String title = d.frontmatter().scalar("title");
            if (title != null && !title.isBlank()) {
                titleByPath.put(d.entry().relativePath(), title);
            }
        }

        // A gone architecture-topic target (typically a frontmatter topics: tag) can only plausibly be
        // another topic or interface document — never a decision, rule, or concept file.
        final List<String> topicDocPaths = docPaths.stream()
                .filter(p -> p.contains("/architecture/topics/") || p.contains("/architecture/interfaces/"))
                .toList();

        final List<String> sourcePaths = new ArrayList<>();
        for (final String basename : result.sourceIndex().basenames()) {
            sourcePaths.addAll(result.sourceIndex().pathsForBasename(basename));
        }

        final NearNameMatcher.Candidates docPool = NearNameMatcher.Candidates.of(docPaths, titleByPath);
        final NearNameMatcher.Candidates topicDocPool = NearNameMatcher.Candidates.of(topicDocPaths, titleByPath);
        final NearNameMatcher.Candidates sourcePool = NearNameMatcher.Candidates.of(sourcePaths, Map.of());

        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — did-you-mean suggestions (gone targets)\n\n");
        sb.append("_The cited target no longer exists. Each hint is a git rename or a near-name match — a "
                + "suggestion, not a fact. Verify before applying._\n\n");

        // Records the coverage lane says have no tunables section — a key-migration hint pointing at one
        // means the row move needs a new section first.
        final Set<String> sectionlessRecordPaths = new HashSet<>();
        for (final Finding f : result.findings()) {
            if (f.kind() == AnchorKind.CONFIG_PREFIX && f.lane() == Lane.COVERAGE_GAP) {
                sectionlessRecordPaths.add(f.target());
            }
        }

        List<ConfigRecords.Owner> configOwners = null;
        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.outcome() != Outcome.ABSENT || f.lane() != Lane.ASSERT) {
                continue;
            }
            final List<String> bullets;
            if (f.kind() == AnchorKind.CONFIG_KEY) {
                if (configOwners == null) {
                    configOwners = ConfigRecords.scan(result.sourceIndex());
                }
                bullets = configKeyBullets(f, configOwners, sectionlessRecordPaths);
            } else {
                final boolean topicTag =
                        f.kind() == AnchorKind.CROSS_DOC_LINK && f.target().contains("/architecture/topics/");
                final NearNameMatcher.Candidates candidates =
                        switch (f.kind()) {
                            case CROSS_DOC_LINK -> topicTag ? topicDocPool : docPool;
                            case SOURCE_PATH, SOURCE_BASENAME -> sourcePool;
                            default -> null;
                        };
                if (candidates == null) {
                    continue;
                }
                final boolean hasPath = f.kind() != AnchorKind.SOURCE_BASENAME;
                final NearNameMatcher.Hints hints =
                        NearNameMatcher.computeHints(f.target(), f.entryPath(), hasPath, candidates, git);
                final String uniqueDocMatch = f.kind() == AnchorKind.CROSS_DOC_LINK && !topicTag
                        ? NearNameMatcher.uniqueBasenameDoc(f.target(), f.entryPath(), docPaths)
                        : null;
                bullets = composeBullets(f, hints, topicTag, uniqueDocMatch);
            }
            if (bullets.isEmpty()) {
                continue;
            }
            any = true;
            Md.findingHeader(sb, f.entryKey(), f.target(), f.entryPath(), null);
            for (final String bullet : bullets) {
                sb.append("- ").append(bullet).append('\n');
            }
            sb.append('\n');
        }
        if (!any) {
            sb.append("_None._\n");
        }
        strandedProse(sb, result);
        return sb.toString();
    }

    /**
     * The grouping key of the stranded-prose scan: a citing entry paired with one old package its cited
     * classes moved out of. Ordered by entry key then package so the rendered sections are stable.
     *
     * @param entryKey the citing entry's key.
     * @param oldPkg   the old dotted package still named in prose.
     */
    private record ProseKey(String entryKey, String oldPkg) implements Comparable<ProseKey> {

        @Override
        public int compareTo(final ProseKey o) {
            final int byEntry = entryKey.compareTo(o.entryKey);
            return byEntry != 0 ? byEntry : oldPkg.compareTo(o.oldPkg);
        }
    }

    /**
     * Appends the stranded-prose hints: docs where a citation's package/path move has a ready rewrite,
     * but a nearby prose line still names the <em>old package</em> — text no rewrite touches, which
     * would contradict the corrected citations the moment {@code --fix} runs. Only exact package
     * mentions are flagged (not FQNs continuing into a type, which are checked as their own anchors),
     * grouped per document and package. Rendered only when at least one hint exists.
     *
     * @param sb     the buffer to append to.
     * @param result the run result.
     */
    private static void strandedProse(final StringBuilder sb, final RunResult result) {
        final Map<String, KbDocument> docsByKey = new HashMap<>();
        for (final KbDocument d : result.documents()) {
            docsByKey.put(d.entry().key(), d);
        }
        // (entry key, old package) → the new packages its movers went to; TreeMap for stable order.
        final Map<ProseKey, Set<String>> movedTo = new TreeMap<>();
        final Map<ProseKey, Finding> representative = new HashMap<>();
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.ASSERT || f.resolvedPath() == null) {
                continue;
            }
            final String oldPkg = f.kind() == AnchorKind.CLASS
                    ? f.target().substring(0, f.target().indexOf("." + f.citedScope()))
                    : SourceIndex.dottedPackageOf(f.target());
            final String newPkg = SourceIndex.dottedPackageOf(f.resolvedPath());
            if (oldPkg == null || oldPkg.equals(newPkg)) {
                continue;
            }
            final ProseKey key = new ProseKey(f.entryKey(), oldPkg);
            movedTo.computeIfAbsent(key, k -> new TreeSet<>()).add(newPkg == null ? f.resolvedPath() : newPkg);
            representative.putIfAbsent(key, f);
        }
        boolean headerWritten = false;
        for (final Map.Entry<ProseKey, Set<String>> e : movedTo.entrySet()) {
            final Finding f = representative.get(e.getKey());
            final String oldPkg = e.getKey().oldPkg();
            final List<Integer> lines = NearNameMatcher.packageMentionLines(docsByKey.get(f.entryKey()), oldPkg);
            if (lines.isEmpty()) {
                continue;
            }
            if (!headerWritten) {
                headerWritten = true;
                sb.append("\n## Prose naming moved packages\n\n");
                sb.append("_Citations of these packages have ready rewrites (see `auto-fix.md`), but prose on the "
                        + "listed lines still names the old package — no mechanical rewrite touches it, so reword "
                        + "it by hand (or via the semantic pass)._\n\n");
            }
            Md.findingHeader(sb, f.entryKey(), oldPkg, f.entryPath(), null);
            final List<String> lineRefs = new ArrayList<>();
            for (final Integer line : lines) {
                lineRefs.add("line " + line);
            }
            sb.append("- still named on ")
                    .append(String.join(", ", lineRefs))
                    .append("; its cited classes moved to: `")
                    .append(String.join("`, `", e.getValue()))
                    .append("`\n\n");
        }
    }

    /**
     * Composes the rendered bullet lines for one gone finding from its raw hints, most useful first: a
     * definite git rename stands alone; otherwise the deleting commit, an actionable slug rename or link
     * rewrite where unambiguous, the near-name matches, and an {@code historical:} nudge for ADR-cited
     * sources.
     *
     * @param f              the gone finding.
     * @param hints          the raw hints for its target.
     * @param topicTag       whether the finding is a frontmatter topics tag (eligible for slug promotion).
     * @param uniqueDocMatch for a body doc link, the single KB doc sharing the gone target's basename, or
     *                       {@code null} when there is none (or more than one).
     * @return the bullet lines, most useful first (possibly empty).
     */
    private static List<String> composeBullets(
            final Finding f, final NearNameMatcher.Hints hints, final boolean topicTag, final String uniqueDocMatch) {
        final List<String> bullets = new ArrayList<>();
        if (hints.gitRename() != null) {
            bullets.add("renamed in git to: `" + hints.gitRename() + "`");
            return bullets;
        }
        if (hints.gitDeletion() != null) {
            bullets.add("deleted in: `" + hints.gitDeletion() + "`");
        }

        String promotedPath = null;
        if (topicTag
                && hints.nearNames().size() == 1
                && hints.nearNames().get(0).score() >= NearNameMatcher.PROMOTE_SIMILARITY) {
            promotedPath = hints.nearNames().get(0).path();
            bullets.add("rename `topics:` slug `" + slug(f.target()) + "` → `" + slug(promotedPath) + "`");
        } else if (uniqueDocMatch != null) {
            promotedPath = uniqueDocMatch;
            bullets.add("unique name match — rewrite the link to `" + relativeHref(f.entryPath(), uniqueDocMatch)
                    + "` (`" + uniqueDocMatch + "`)");
        }

        int shown = 0;
        for (final NearNameMatcher.Scored s : hints.nearNames()) {
            if (shown >= MAX_SUGGESTIONS) {
                break;
            }
            if (s.path().equals(promotedPath)) {
                continue;
            }
            bullets.add("similar name: `" + s.path() + "`");
            shown++;
        }

        if ((f.kind() == AnchorKind.SOURCE_PATH || f.kind() == AnchorKind.SOURCE_BASENAME)
                && f.entryType() == EntryType.DECISION) {
            bullets.add("cited by an ADR — if this code was deliberately removed, mark it `historical:` in the "
                    + "frontmatter instead of repointing");
        }
        return bullets;
    }

    /**
     * Composes the bullets for a gone config key from the matcher's ranked matches: other indexed
     * {@code @ConfigData} records declaring a component with the same name (a key migration — strong,
     * actionable) or a similar one above the promotion bar (a possible rename). A hint, never a fact (see
     * {@link NearNameMatcher#configKeyMatches}). A migration hint whose target record the coverage lane
     * lists as sectionless says so — moving the row needs a new catalog section, not just a key rewrite.
     *
     * @param f                      the gone config-key finding; its target is the fully-qualified documented key.
     * @param owners                 every indexed config record.
     * @param sectionlessRecordPaths repo-relative paths of records the tunables catalog has no section for.
     * @return the bullet lines, exact matches first (possibly empty).
     */
    private static List<String> configKeyBullets(
            final Finding f, final List<ConfigRecords.Owner> owners, final Set<String> sectionlessRecordPaths) {
        final List<String> bullets = new ArrayList<>();
        for (final NearNameMatcher.KeyMatch m : NearNameMatcher.configKeyMatches(f.target(), owners)) {
            if (bullets.size() >= MAX_SUGGESTIONS) {
                break;
            }
            final String fqKey = m.owner().type().fullyQualifiedKey(m.keyName());
            if (m.score() == 1.0) {
                final String sectionless =
                        sectionlessRecordPaths.contains(m.owner().path())
                                ? " — this record has no tunables section yet (see `coverage.md`); moving the row needs a"
                                        + " new section"
                                : "";
                bullets.add("key `" + m.keyName() + "` is now declared by `"
                        + m.owner().className() + "` — full key `" + fqKey + "` (`"
                        + m.owner().path() + "`)" + sectionless);
            } else {
                bullets.add("similar key: `" + fqKey + "` in `" + m.owner().className() + "` (`"
                        + m.owner().path() + "`)");
            }
        }
        return bullets;
    }

    /**
     * The relative href from a citing doc to a target doc (both repo-relative), as it would be written
     * in a markdown link.
     *
     * @param fromDocPath the citing doc's repo-relative path.
     * @param toDocPath   the target doc's repo-relative path.
     * @return the forward-slashed relative href.
     */
    private static String relativeHref(final String fromDocPath, final String toDocPath) {
        final java.nio.file.Path fromDir = java.nio.file.Path.of(fromDocPath).getParent();
        final java.nio.file.Path to = java.nio.file.Path.of(toDocPath);
        final java.nio.file.Path rel = fromDir == null ? to : fromDir.relativize(to);
        return rel.toString().replace('\\', '/');
    }

    /**
     * The bare slug of a doc path: its basename without the {@code .md} extension (e.g.
     * {@code .../topics/restart-and-pces.md} to {@code restart-and-pces}).
     *
     * @param docPath the doc path.
     * @return the slug used in a frontmatter {@code topics:} list.
     */
    private static String slug(final String docPath) {
        return RepoPaths.stripExtension(RepoPaths.lastSegment(docPath));
    }
}
