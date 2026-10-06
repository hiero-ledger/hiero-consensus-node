// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.resolve.ConfigRecords;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ConfigComponent;
import org.hiero.consensus.kbfreshness.util.Markdown;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * The near-name scoring engine behind the did-you-mean suggestions: given a gone target and a candidate
 * pool (KB docs, source paths, or config-record components), it ranks the plausible successors by name
 * similarity, combining significant-token overlap, normalized edit distance, and two weaker-but-identifying
 * signals (frontmatter-title token coverage and pool-unique distinctive tokens). It is pure scoring — it
 * makes no rendering decisions and asserts nothing; {@code SuggestionsRenderer} composes its output into
 * hints. Deterministic for a given checkout.
 */
public final class NearNameMatcher {

    /** Minimum similarity for a near-name match to be offered. */
    private static final double THRESHOLD = 0.5;
    /**
     * Score for a weaker-but-identifying signal (title-token coverage or a pool-unique token): offered,
     * never promoted.
     */
    private static final double WEAK_SIGNAL = 0.55;
    /** Cap for title-token coverage so a title match alone can never reach promotion strength. */
    private static final double TITLE_CAP = 0.65;
    /** Minimum length of a shared token for the unique-token signal (short tokens identify nothing). */
    private static final int UNIQUE_TOKEN_MIN_LENGTH = 4;
    /** Minimum edit similarity for a head-token-differing match, high enough that only a near-certain typo counts. */
    private static final double HIGH_EDIT_SIM = 0.8;
    /**
     * Minimum similarity (with a uniqueness check) to promote a match to an actionable rename; also the
     * config-key match bar.
     */
    public static final double PROMOTE_SIMILARITY = 0.7;

    /** Prevents instantiation of this static-only matcher. */
    private NearNameMatcher() {}

    /** A candidate path with its similarity score, for ranking. */
    public record Scored(String path, double score) {}

    /**
     * A prepared candidate pool: the paths, each path's significant tokens (filename tokens plus, for KB
     * docs, frontmatter-title tokens), and how many candidates carry each token (for the unique-token
     * signal). Built once per pool and reused across findings.
     *
     * @param paths        the candidate paths.
     * @param tokensByPath each path's token set.
     * @param tokenFreq    the number of candidates whose token set contains each token.
     */
    public record Candidates(
            List<String> paths, Map<String, Set<String>> tokensByPath, Map<String, Integer> tokenFreq) {

        /**
         * Prepares a candidate pool.
         *
         * @param paths       the candidate paths.
         * @param titleByPath frontmatter titles keyed by doc path (empty for source pools).
         * @return the prepared pool.
         */
        public static Candidates of(final List<String> paths, final Map<String, String> titleByPath) {
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
    public record Hints(String gitRename, String gitDeletion, List<Scored> nearNames) {}

    /**
     * One config-record component matching a gone config key by name: an exact same-named declaration
     * (score {@code 1.0}) or a near-name declaration above {@link #PROMOTE_SIMILARITY}.
     *
     * @param score   the match strength ({@code 1.0} for an exact same-name match).
     * @param owner   the config record declaring the component.
     * @param keyName the matching component's property name.
     */
    public record KeyMatch(double score, ConfigRecords.Owner owner, String keyName) {}

    /**
     * Computes the raw hints for a gone target: a definite git rename when available (which suppresses
     * near-name guessing), else the deleting commit when git recorded one, plus the near-name candidates
     * above {@link #THRESHOLD} (also scoring the title-token and pool-unique-token signals).
     *
     * @param gone       the gone target (a repo-relative path, or a bare basename).
     * @param self       the path of the entry that made the citation, excluded from candidates.
     * @param hasPath    whether {@code gone} is a real path git can trace (false for bare basenames).
     * @param candidates the prepared candidate pool to match against.
     * @param git        the git wrapper, or {@code null} to skip rename/deletion detection.
     * @return the raw hints.
     */
    public static Hints computeHints(
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
     * Ranks the config records declaring a component that matches a gone config key: exact same-named
     * declarations (the shared migration scan) score {@code 1.0}; near-name declarations above
     * {@link #PROMOTE_SIMILARITY} are scored against the remaining components. Sorted best-first, then by
     * owner path and property name for stability.
     *
     * @param goneFqKey the gone fully-qualified documented key.
     * @param owners    every indexed config record.
     * @return the matches, exact matches first (possibly empty).
     */
    public static List<KeyMatch> configKeyMatches(final String goneFqKey, final List<ConfigRecords.Owner> owners) {
        final String goneProp = lastDotSegment(goneFqKey);
        final List<KeyMatch> matches = new ArrayList<>();
        for (final ConfigRecords.Owner owner : ConfigRecords.declaringRecordsOf(owners, goneFqKey)) {
            matches.add(new KeyMatch(1.0, owner, goneProp));
        }
        for (final ConfigRecords.Owner owner : owners) {
            for (final ConfigComponent c : owner.type().configComponents()) {
                if (c.keyName().equals(goneProp)) {
                    continue; // exact matches are handled above.
                }
                if (owner.type().fullyQualifiedKey(c.keyName()).equals(goneFqKey)) {
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
        return matches;
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
    public static String uniqueBasenameDoc(final String gone, final String self, final List<String> docPaths) {
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
     * The 1-based non-fenced lines of a document mentioning a package exactly — the package name not
     * followed by a dot or word character, so an FQN continuing into a type (checked as its own anchor)
     * or a deeper subpackage never counts.
     *
     * @param doc    the citing document, possibly {@code null}.
     * @param pkg    the dotted package to look for.
     * @return the matching lines, in order (possibly empty).
     */
    public static List<Integer> packageMentionLines(final KbDocument doc, final String pkg) {
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
}
