// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.ConfigRecords;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ConfigComponent;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.Markdown;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * Renders non-asserting "did you mean" hints for gone targets: a cited doc, source path, bare source
 * file, or config key no longer resolves. Each hint is either a definite git rename or a near-name match
 * against the KB docs / source index / indexed config records — a suggestion, never a fact, so it
 * respects the "never assert" invariant and is kept out of the machine artifact. Where a hint is
 * unambiguous it is made actionable — a topics-slug rename with a single strong match, a doc link whose
 * basename resolves at exactly one other KB doc (a ready link rewrite), a gone config key declared
 * same-named by another record (a key migration), or (for a source an ADR cites as removed) a nudge to
 * mark it {@code historical:}. A closing section lists prose lines still naming the old package of a
 * moved citation — text the ready rewrites cannot touch. Deterministic for a given checkout.
 */
public final class SuggestionsRenderer {

    /** Minimum similarity for a near-name match to be offered. */
    private static final double THRESHOLD = 0.5;
    /**
     * Score assigned to weaker-but-identifying signals: a candidate whose frontmatter title covers the
     * gone name's tokens, or one sharing a token that occurs in exactly one candidate. Above
     * {@link #THRESHOLD} (offered) but below {@link #PROMOTE_SIMILARITY} — such a hint is never promoted
     * to an actionable rename on its own.
     */
    private static final double WEAK_SIGNAL = 0.55;
    /** Cap for title-token coverage so a title match alone can never reach promotion strength. */
    private static final double TITLE_CAP = 0.65;
    /** Minimum length of a shared token for the unique-token signal (short tokens identify nothing). */
    private static final int UNIQUE_TOKEN_MIN_LENGTH = 4;
    /**
     * Minimum edit similarity for a match whose head tokens differ. Long identifiers accumulate
     * incidental character overlap, so a plain edit-distance signal only counts on its own when it is
     * near-certain (a typo), not merely above {@link #THRESHOLD}.
     */
    private static final double HIGH_EDIT_SIM = 0.8;
    /**
     * Minimum similarity for a topics-slug match to be promoted to an actionable rename. Combined with a
     * uniqueness check (exactly one candidate above {@link #THRESHOLD}), this keeps the promotion to cases
     * like {@code pces → restart-and-pces} while leaving genuinely ambiguous slugs (two plausible topics)
     * as plain near-name hints.
     */
    private static final double PROMOTE_SIMILARITY = 0.7;
    /** Maximum number of near-name suggestions offered per gone target. */
    private static final int MAX_SUGGESTIONS = 3;

    /** Prevents instantiation of this static-only renderer. */
    private SuggestionsRenderer() {}

    /** A candidate path with its similarity score, for ranking. */
    private record Scored(String path, double score) {}

    /**
     * A prepared candidate pool: the paths, each path's significant tokens (filename tokens plus, for KB
     * docs, frontmatter-title tokens), and how many candidates carry each token (for the unique-token
     * signal). Built once per pool and reused across findings.
     *
     * @param paths        the candidate paths.
     * @param tokensByPath each path's token set.
     * @param tokenFreq    the number of candidates whose token set contains each token.
     */
    private record Candidates(
            List<String> paths, Map<String, Set<String>> tokensByPath, Map<String, Integer> tokenFreq) {

        /**
         * Prepares a candidate pool.
         *
         * @param paths       the candidate paths.
         * @param titleByPath frontmatter titles keyed by doc path (empty for source pools).
         * @return the prepared pool.
         */
        static Candidates of(final List<String> paths, final Map<String, String> titleByPath) {
            final Map<String, Set<String>> tokensByPath = new HashMap<>();
            final Map<String, Integer> tokenFreq = new HashMap<>();
            for (final String p : paths) {
                final Set<String> tokens = new TreeSet<>(sigTokens(RepoPaths.stripExtension(RepoPaths.lastSegment(p))));
                final String title = titleByPath.get(p);
                if (title != null) {
                    tokens.addAll(sigTokens(title));
                }
                if (tokensByPath.putIfAbsent(p, tokens) == null) {
                    for (final String t : tokens) {
                        tokenFreq.merge(t, 1, Integer::sum);
                    }
                }
            }
            return new Candidates(paths, tokensByPath, tokenFreq);
        }
    }

    /**
     * The raw hints computed for a gone target before finding-specific composition: a definite git rename
     * (which suppresses near-name guessing), the deleting commit when git recorded one, and the near-name
     * candidates above the threshold.
     *
     * @param gitRename  the path the target was renamed to, or {@code null}.
     * @param gitDeletion the {@code "<hash> <subject>"} of the deleting commit, or {@code null}.
     * @param nearNames  the near-name candidates above the threshold, best first (empty on a rename).
     */
    private record Hints(String gitRename, String gitDeletion, List<Scored> nearNames) {}

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

        final Candidates docPool = Candidates.of(docPaths, titleByPath);
        final Candidates topicDocPool = Candidates.of(topicDocPaths, titleByPath);
        final Candidates sourcePool = Candidates.of(sourcePaths, Map.of());

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
                final Candidates candidates =
                        switch (f.kind()) {
                            case CROSS_DOC_LINK -> topicTag ? topicDocPool : docPool;
                            case SOURCE_PATH, SOURCE_BASENAME -> sourcePool;
                            default -> null;
                        };
                if (candidates == null) {
                    continue;
                }
                final boolean hasPath = f.kind() != AnchorKind.SOURCE_BASENAME;
                final Hints hints = computeHints(f.target(), f.entryPath(), hasPath, candidates, git);
                final String uniqueDocMatch = f.kind() == AnchorKind.CROSS_DOC_LINK && !topicTag
                        ? uniqueBasenameDoc(f.target(), f.entryPath(), docPaths)
                        : null;
                bullets = composeBullets(f, hints, topicTag, uniqueDocMatch);
            }
            if (bullets.isEmpty()) {
                continue;
            }
            any = true;
            sb.append("### `")
                    .append(f.entryKey())
                    .append("` — `")
                    .append(f.target())
                    .append("`\n");
            sb.append("`").append(f.entryPath()).append("`\n\n");
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
        // (entry key | old package) → the new packages its movers went to; TreeMap for stable order.
        final Map<String, Set<String>> movedTo = new TreeMap<>();
        final Map<String, Finding> representative = new HashMap<>();
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
            final String key = f.entryKey() + "|" + oldPkg;
            movedTo.computeIfAbsent(key, k -> new TreeSet<>()).add(newPkg == null ? f.resolvedPath() : newPkg);
            representative.putIfAbsent(key, f);
        }
        boolean headerWritten = false;
        for (final Map.Entry<String, Set<String>> e : movedTo.entrySet()) {
            final Finding f = representative.get(e.getKey());
            final String oldPkg = e.getKey().substring(e.getKey().indexOf('|') + 1);
            final List<Integer> lines = packageMentionLines(docsByKey.get(f.entryKey()), oldPkg);
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
            sb.append("### `")
                    .append(f.entryKey())
                    .append("` — `")
                    .append(oldPkg)
                    .append("`\n");
            sb.append("`").append(f.entryPath()).append("`\n\n");
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
     * The 1-based non-fenced lines of a document mentioning a package exactly — the package name not
     * followed by a dot or word character, so an FQN continuing into a type (checked as its own anchor)
     * or a deeper subpackage never counts.
     *
     * @param doc    the citing document, possibly {@code null}.
     * @param pkg    the dotted package to look for.
     * @return the matching lines, in order (possibly empty).
     */
    private static List<Integer> packageMentionLines(final KbDocument doc, final String pkg) {
        final List<Integer> lines = new ArrayList<>();
        if (doc == null) {
            return lines;
        }
        final Pattern mention = Pattern.compile(Pattern.quote(pkg) + "(?![.\\w])");
        boolean inFence = false;
        for (int i = 0; i < doc.lines().size(); i++) {
            final String line = doc.lines().get(i);
            if (Markdown.isFenceDelimiter(line)) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && mention.matcher(line).find()) {
                lines.add(i + 1);
            }
        }
        return lines;
    }

    /**
     * Composes the rendered bullet lines for one gone finding from its raw hints. A definite git rename is
     * conclusive and stands alone; otherwise the deleting commit, an actionable topics-slug rename (when a
     * single strong match exists), an actionable link rewrite (when the linked doc's basename resolves at
     * exactly one other KB doc), the near-name matches, and — for a source an ADR cites — a nudge to mark
     * it {@code historical:} are offered in that order.
     *
     * @param f              the gone finding.
     * @param hints          the raw hints for its target.
     * @param topicTag       whether the finding is a frontmatter topics tag (eligible for slug promotion).
     * @param uniqueDocMatch for a body doc link, the single KB doc sharing the gone target's basename, or
     *                       {@code null} when there is none (or more than one).
     * @return the bullet lines, most useful first (possibly empty).
     */
    private static List<String> composeBullets(
            final Finding f, final Hints hints, final boolean topicTag, final String uniqueDocMatch) {
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
                && hints.nearNames().get(0).score() >= PROMOTE_SIMILARITY) {
            promotedPath = hints.nearNames().get(0).path();
            bullets.add("rename `topics:` slug `" + slug(f.target()) + "` → `" + slug(promotedPath) + "`");
        } else if (uniqueDocMatch != null) {
            promotedPath = uniqueDocMatch;
            bullets.add("unique name match — rewrite the link to `" + relativeHref(f.entryPath(), uniqueDocMatch)
                    + "` (`" + uniqueDocMatch + "`)");
        }

        int shown = 0;
        for (final Scored s : hints.nearNames()) {
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
     * Composes the hints for a gone config key: other indexed {@code @ConfigData} records declaring a
     * component with the same name (a key migration — strong, actionable) or a similar one (a possible
     * rename). Matched purely against declared record components; a hint, never a fact. The similarity
     * bar is {@link #PROMOTE_SIMILARITY}, not the looser near-name {@link #THRESHOLD} — config keys are
     * short, so weak token overlap (e.g. a shared {@code Detector}) identifies nothing. A migration hint
     * whose target record the coverage lane lists as sectionless says so — moving the row needs a new
     * catalog section, not just a key rewrite.
     *
     * @param f                      the gone config-key finding; its target is the fully-qualified documented key.
     * @param owners                 every indexed config record.
     * @param sectionlessRecordPaths repo-relative paths of records the tunables catalog has no section for.
     * @return the bullet lines, exact matches first (possibly empty).
     */
    private static List<String> configKeyBullets(
            final Finding f, final List<ConfigRecords.Owner> owners, final Set<String> sectionlessRecordPaths) {
        final String goneProp = lastDotSegment(f.target());
        record KeyMatch(double score, ConfigRecords.Owner owner, String keyName) {}
        final List<KeyMatch> matches = new ArrayList<>();
        // Exact same-named declarations (the shared migration scan) score 1.0; near-name matches — a
        // suggestions-only feature — are scored separately against the remaining components.
        for (final ConfigRecords.Owner owner : ConfigRecords.declaringRecordsOf(owners, f.target())) {
            matches.add(new KeyMatch(1.0, owner, goneProp));
        }
        for (final ConfigRecords.Owner owner : owners) {
            for (final ConfigComponent c : owner.type().configComponents()) {
                if (c.keyName().equals(goneProp)) {
                    continue; // exact matches are handled above.
                }
                if (owner.type().fullyQualifiedKey(c.keyName()).equals(f.target())) {
                    continue; // the documented key itself — it would not be gone.
                }
                final double score = similarity(goneProp, c.keyName());
                if (score >= PROMOTE_SIMILARITY) {
                    matches.add(new KeyMatch(score, owner, c.keyName()));
                }
            }
        }
        matches.sort(Comparator.comparingDouble((KeyMatch m) -> -m.score())
                .thenComparing(m -> m.owner().path())
                .thenComparing(KeyMatch::keyName));
        final List<String> bullets = new ArrayList<>();
        for (final KeyMatch m : matches) {
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
                bullets.add("key `" + goneProp + "` is now declared by `"
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
     * The single KB doc sharing a gone doc-link target's basename, or {@code null} when none or several
     * do. Exactly-one is what makes the rewrite hint actionable: the link was written against the wrong
     * directory, and only one document it could have meant exists.
     *
     * @param gone     the gone link target (repo-relative).
     * @param self     the citing entry's path, excluded from candidates.
     * @param docPaths every scanned KB doc path.
     * @return the unique same-basename doc path, or {@code null}.
     */
    private static String uniqueBasenameDoc(final String gone, final String self, final List<String> docPaths) {
        final String basename = RepoPaths.lastSegment(gone);
        String match = null;
        for (final String p : docPaths) {
            if (!p.equals(gone) && !p.equals(self) && RepoPaths.lastSegment(p).equals(basename)) {
                if (match != null) {
                    return null;
                }
                match = p;
            }
        }
        return match;
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
     * The last dot-separated segment of a fully-qualified config key (the bare property name).
     *
     * @param fqKey the fully-qualified key.
     * @return the property name.
     */
    private static String lastDotSegment(final String fqKey) {
        final int dot = fqKey.lastIndexOf('.');
        return dot >= 0 ? fqKey.substring(dot + 1) : fqKey;
    }

    /**
     * Computes the raw hints for a gone target: a definite git rename when available (which suppresses
     * near-name guessing), else the deleting commit when git recorded one, plus the near-name candidates
     * above the similarity threshold. Beyond plain name similarity, two weaker-but-identifying signals
     * are scored (both capped below promotion strength): a candidate whose tokens — including its
     * frontmatter title's — cover the gone name's tokens, and a candidate sharing a token that occurs in
     * exactly one candidate (a distinctive token like {@code execution} pinpoints its owner even when
     * edit distance sees nothing).
     *
     * @param gone       the gone target (a repo-relative path, or a bare basename).
     * @param self       the path of the entry that made the citation, excluded from candidates.
     * @param hasPath    whether {@code gone} is a real path git can trace (false for bare basenames).
     * @param candidates the prepared candidate pool to match against.
     * @param git        the git wrapper, or {@code null} to skip rename/deletion detection.
     * @return the raw hints.
     */
    private static Hints computeHints(
            final String gone, final String self, final boolean hasPath, final Candidates candidates, final Git git) {
        String rename = null;
        String deletion = null;
        if (git != null) {
            if (hasPath && !gone.contains("/.../")) {
                final String renamed = git.findRename(gone);
                if (renamed != null && !renamed.equals(gone)) {
                    rename = renamed;
                }
            }
            if (rename == null) {
                // No rename recorded — a deletion commit explains where the target went (or that it is
                // simply gone). Bare and abbreviated citations are traced by basename pathspec.
                final String pathspec = hasPath && !gone.contains("/.../") ? gone : "*/" + RepoPaths.lastSegment(gone);
                deletion = git.findDeletion(pathspec);
            }
        }

        final List<Scored> scored = new ArrayList<>();
        if (rename == null) {
            final String goneStem = RepoPaths.stripExtension(RepoPaths.lastSegment(gone));
            final Set<String> goneTokens = sigTokens(goneStem);
            final Set<String> seen = new HashSet<>();
            for (final String cand : candidates.paths()) {
                if (cand.equals(gone) || cand.equals(self)) {
                    continue;
                }
                double score = similarity(goneStem, RepoPaths.stripExtension(RepoPaths.lastSegment(cand)));
                final Set<String> candTokens = candidates.tokensByPath().get(cand);
                score = Math.max(score, tokenSignals(goneTokens, candTokens, candidates.tokenFreq()));
                if (score >= THRESHOLD && seen.add(cand)) {
                    scored.add(new Scored(cand, score));
                }
            }
            scored.sort(Comparator.comparingDouble((Scored s) -> -s.score()).thenComparing(Scored::path));
        }
        return new Hints(rename, deletion, scored);
    }

    /**
     * The token-level score of a candidate against a gone name: how much of the gone name's token set
     * the candidate covers (filename plus title tokens, capped at {@link #TITLE_CAP}), floored at
     * {@link #WEAK_SIGNAL} when the two share a distinctive token — one carried by exactly one candidate
     * in the pool and long enough to identify it.
     *
     * @param goneTokens the gone name's significant tokens.
     * @param candTokens the candidate's tokens (filename and title).
     * @param tokenFreq  how many candidates in the pool carry each token.
     * @return the token-signal score in {@code [0, TITLE_CAP]}.
     */
    private static double tokenSignals(
            final Set<String> goneTokens, final Set<String> candTokens, final Map<String, Integer> tokenFreq) {
        if (goneTokens.isEmpty() || candTokens == null || candTokens.isEmpty()) {
            return 0;
        }
        final Set<String> shared = new TreeSet<>(goneTokens);
        shared.retainAll(candTokens);
        if (shared.isEmpty()) {
            return 0;
        }
        double score = Math.min(TITLE_CAP, (double) shared.size() / goneTokens.size() * TITLE_CAP);
        for (final String t : shared) {
            if (t.length() >= UNIQUE_TOKEN_MIN_LENGTH && tokenFreq.getOrDefault(t, 0) == 1) {
                score = Math.max(score, WEAK_SIGNAL);
                break;
            }
        }
        return score;
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

    /**
     * A similarity score in {@code [0, 1]} combining significant-token overlap and normalized edit
     * distance, taking the stronger signal. Token overlap catches suffix/prefix matches (e.g.
     * {@code pces} within {@code restart-and-pces}) that raw edit distance misses. The edit signal
     * counts only when the head (last significant) tokens agree, or when it is near-certain — long
     * identifiers otherwise accumulate enough incidental overlap to cross the threshold for
     * semantically unrelated names.
     *
     * @param a the first stem.
     * @param b the second stem.
     * @return the similarity score.
     */
    static double similarity(final String a, final String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        final String la = a.toLowerCase(Locale.ROOT);
        final String lb = b.toLowerCase(Locale.ROOT);
        if (la.equals(lb)) {
            return 1.0;
        }
        double tokenScore = 0;
        final Set<String> ta = sigTokens(a);
        final Set<String> tb = sigTokens(b);
        if (!ta.isEmpty() && !tb.isEmpty()) {
            final Set<String> shared = new TreeSet<>(ta);
            shared.retainAll(tb);
            if (!shared.isEmpty()) {
                final int minSize = Math.min(ta.size(), tb.size());
                final double coverage = (double) shared.size() / Math.max(ta.size(), tb.size());
                tokenScore = shared.size() == minSize ? Math.max(0.7, coverage) : coverage;
            }
        }
        final double editSim = 1.0 - (double) levenshtein(la, lb) / Math.max(la.length(), lb.length());
        final boolean editApplies = headToken(a).equals(headToken(b)) || editSim >= HIGH_EDIT_SIM;
        return Math.max(tokenScore, editApplies ? editSim : 0);
    }

    /**
     * The head token of a stem: its last significant token (e.g. {@code generation} of
     * {@code NonDeterministicGeneration}), or the lowercased stem when it has none.
     *
     * @param stem the stem.
     * @return the head token.
     */
    private static String headToken(final String stem) {
        final String spaced = stem.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[-_./]", " ");
        final String[] parts = spaced.toLowerCase(Locale.ROOT).strip().split("\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].length() >= 3) {
                return parts[i];
            }
        }
        return stem.toLowerCase(Locale.ROOT);
    }

    /**
     * The significant tokens of a stem: split on {@code -}/{@code _}/{@code .}/{@code /} and camelCase
     * boundaries, lowercased, keeping only tokens of length ≥ 3 (dropping noise like {@code of}/{@code to}).
     *
     * @param stem the stem to tokenize.
     * @return the significant tokens.
     */
    private static Set<String> sigTokens(final String stem) {
        final Set<String> tokens = new TreeSet<>();
        final String spaced = stem.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[-_./]", " ");
        for (final String t : spaced.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (t.length() >= 3) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * The Levenshtein edit distance between two strings.
     *
     * @param a the first string.
     * @param b the second string.
     * @return the minimum single-character edits to turn {@code a} into {@code b}.
     */
    private static int levenshtein(final String a, final String b) {
        final int[] prev = new int[b.length() + 1];
        final int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }
}
