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
import java.util.TreeSet;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;

/**
 * Renders non-asserting "did you mean" hints for gone targets: a cited doc, source path, or bare source
 * file no longer resolves. Each hint is either a definite git rename or a near-name match against the KB
 * docs / source index — a suggestion, never a fact, so it respects the "never assert" invariant and is
 * kept out of the machine artifact. Where a hint is unambiguous it is made actionable — a topics-slug
 * rename with a single strong match, or (for a source an ADR cites as removed) a nudge to mark it
 * {@code historical:}. Deterministic for a given checkout.
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
                final Set<String> tokens = new TreeSet<>(sigTokens(stem(baseName(p))));
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

        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.outcome() != Outcome.ABSENT || f.lane() != Lane.ASSERT) {
                continue;
            }
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
            final List<String> bullets = composeBullets(f, hints, topicTag);
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
        return sb.toString();
    }

    /**
     * Composes the rendered bullet lines for one gone finding from its raw hints. A definite git rename is
     * conclusive and stands alone; otherwise the deleting commit, an actionable topics-slug rename (when a
     * single strong match exists), the near-name matches, and — for a source an ADR cites — a nudge to mark
     * it {@code historical:} are offered in that order.
     *
     * @param f        the gone finding.
     * @param hints    the raw hints for its target.
     * @param topicTag whether the finding is a frontmatter topics tag (eligible for slug promotion).
     * @return the bullet lines, most useful first (possibly empty).
     */
    private static List<String> composeBullets(final Finding f, final Hints hints, final boolean topicTag) {
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
                final String pathspec = hasPath && !gone.contains("/.../") ? gone : "*/" + baseName(gone);
                deletion = git.findDeletion(pathspec);
            }
        }

        final List<Scored> scored = new ArrayList<>();
        if (rename == null) {
            final String goneStem = stem(baseName(gone));
            final Set<String> goneTokens = sigTokens(goneStem);
            final Set<String> seen = new HashSet<>();
            for (final String cand : candidates.paths()) {
                if (cand.equals(gone) || cand.equals(self)) {
                    continue;
                }
                double score = similarity(goneStem, stem(baseName(cand)));
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
        return stem(baseName(docPath));
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
     * The last {@code /}-separated segment of a path (or the whole string if it has no slash).
     *
     * @param path the path.
     * @return the final segment.
     */
    private static String baseName(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * A filename with its first-dot extension removed.
     *
     * @param name the filename.
     * @return the name up to the first dot, or the whole name if it has none.
     */
    private static String stem(final String name) {
        final int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
