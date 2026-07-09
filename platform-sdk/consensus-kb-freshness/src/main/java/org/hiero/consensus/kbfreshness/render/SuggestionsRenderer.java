// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;

/**
 * Renders non-asserting "did you mean" hints for gone targets: a cited doc, source path, or bare source
 * file no longer resolves. Each hint is either a definite git rename or a near-name match against the KB
 * docs / source index — a suggestion, never a fact, so it respects the "never assert" invariant and is
 * kept out of the machine artifact. Deterministic for a given checkout.
 */
public final class SuggestionsRenderer {

    /** Minimum similarity for a near-name match to be offered. */
    private static final double THRESHOLD = 0.5;
    /** Maximum number of near-name suggestions offered per gone target. */
    private static final int MAX_SUGGESTIONS = 3;

    /** Prevents instantiation of this static-only renderer. */
    private SuggestionsRenderer() {}

    /** A suggested replacement path with the reason it was offered. */
    private record Suggestion(String path, String reason) {}

    /** A candidate path with its similarity score, for ranking. */
    private record Scored(String path, double score) {}

    /**
     * Renders the did-you-mean suggestions as Markdown.
     *
     * @param result the run result.
     * @param git    the git wrapper for rename detection (may report unavailable).
     * @return the rendered Markdown suggestions.
     */
    public static String render(final RunResult result, final Git git) {
        final List<String> docPaths = new ArrayList<>();
        result.documents().forEach(d -> docPaths.add(d.entry().relativePath()));

        final List<String> sourcePaths = new ArrayList<>();
        for (final String basename : result.sourceIndex().basenames()) {
            sourcePaths.addAll(result.sourceIndex().pathsForBasename(basename));
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — did-you-mean suggestions (gone targets)\n\n");
        sb.append("_The cited target no longer exists. Each hint is a git rename or a near-name match — a "
                + "suggestion, not a fact. Verify before applying._\n\n");

        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.outcome() != Outcome.ABSENT || f.lane() != Lane.ASSERT) {
                continue;
            }
            final List<Suggestion> suggestions =
                    switch (f.kind()) {
                        case CROSS_DOC_LINK -> suggest(f.target(), f.entryPath(), true, docPaths, git);
                        case SOURCE_PATH -> suggest(f.target(), f.entryPath(), true, sourcePaths, git);
                        case SOURCE_BASENAME -> suggest(f.target(), f.entryPath(), false, sourcePaths, null);
                        default -> List.of();
                    };
            if (suggestions.isEmpty()) {
                continue;
            }
            any = true;
            sb.append("### `")
                    .append(f.entryKey())
                    .append("` — `")
                    .append(f.target())
                    .append("`\n");
            sb.append("`").append(f.entryPath()).append("`\n\n");
            for (final Suggestion s : suggestions) {
                sb.append("- ")
                        .append(s.reason())
                        .append(": `")
                        .append(s.path())
                        .append("`\n");
            }
            sb.append('\n');
        }
        if (!any) {
            sb.append("_None._\n");
        }
        return sb.toString();
    }

    /**
     * Suggests replacements for a gone target: a definite git rename when available, else the closest
     * near-name matches above the similarity threshold.
     *
     * @param gone           the gone target (a repo-relative path, or a bare basename).
     * @param self           the path of the entry that made the citation, excluded from candidates.
     * @param hasPath        whether {@code gone} is a real path git can trace (false for bare basenames).
     * @param candidatePaths the repo-relative paths to match against.
     * @param git            the git wrapper, or {@code null} to skip rename detection.
     * @return the suggestions, most relevant first (possibly empty).
     */
    private static List<Suggestion> suggest(
            final String gone,
            final String self,
            final boolean hasPath,
            final List<String> candidatePaths,
            final Git git) {
        if (hasPath && git != null) {
            final String renamed = git.findRename(gone);
            if (renamed != null && !renamed.equals(gone)) {
                return List.of(new Suggestion(renamed, "renamed in git to"));
            }
        }

        final String goneStem = stem(baseName(gone));
        final List<Scored> scored = new ArrayList<>();
        for (final String cand : candidatePaths) {
            if (cand.equals(gone) || cand.equals(self)) {
                continue;
            }
            final double score = similarity(goneStem, stem(baseName(cand)));
            if (score >= THRESHOLD) {
                scored.add(new Scored(cand, score));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> -s.score()).thenComparing(Scored::path));

        final List<Suggestion> suggestions = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final Scored s : scored) {
            if (suggestions.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (seen.add(s.path())) {
                suggestions.add(new Suggestion(s.path(), "similar name"));
            }
        }
        return suggestions;
    }

    /**
     * A similarity score in {@code [0, 1]} combining significant-token overlap and normalized edit
     * distance, taking the stronger signal. Token overlap catches suffix/prefix matches (e.g.
     * {@code pces} within {@code restart-and-pces}) that raw edit distance misses.
     *
     * @param a the first stem.
     * @param b the second stem.
     * @return the similarity score.
     */
    private static double similarity(final String a, final String b) {
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
        return Math.max(tokenScore, editSim);
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
