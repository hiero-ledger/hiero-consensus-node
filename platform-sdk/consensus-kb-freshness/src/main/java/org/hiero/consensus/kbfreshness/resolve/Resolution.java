// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;

/**
 * The result of resolving one anchor.
 *
 * @param outcome      the three-valued existence result.
 * @param lane         where the finding is routed, or {@code null} when the anchor resolved cleanly and
 *                     emits no finding (present and correct).
 * @param question     the exact question asked.
 * @param evidence     a one-look, curator-verifiable justification (empty when no finding).
 * @param autoFixLine  for {@link Lane#AUTO_FIX}, the corrected code line; otherwise {@code null}.
 * @param resolvedPath for a package/path move with exactly one candidate, the repo-relative path the
 *                     cited source actually resolves at (drives a path-rewrite auto-fix proposal);
 *                     otherwise {@code null}.
 * @param autoFixSymbol for a {@code File.java:NN} reference on a declaration line, the symbol name it
 *                     migrates to (drives a {@code :NN}→{@code #symbol} auto-fix); otherwise {@code null}.
 */
public record Resolution(
        Outcome outcome,
        Lane lane,
        String question,
        String evidence,
        Integer autoFixLine,
        String resolvedPath,
        String autoFixSymbol) {

    /**
     * A clean resolution that emits no finding (present and correct).
     *
     * @param outcome  the existence result.
     * @param question the question asked.
     * @return a resolution with no lane, no evidence, and no auto-fix line.
     */
    public static Resolution ok(final Outcome outcome, final String question) {
        return new Resolution(outcome, null, question, "", null, null, null);
    }

    /**
     * A resolution that emits a finding routed to the given lane.
     *
     * @param outcome  the existence result.
     * @param lane     where the finding is routed.
     * @param question the question asked.
     * @param evidence the curator-verifiable justification.
     * @return a resolution carrying the finding, with no auto-fix line.
     */
    public static Resolution finding(
            final Outcome outcome, final Lane lane, final String question, final String evidence) {
        return new Resolution(outcome, lane, question, evidence, null, null, null);
    }

    /**
     * A present-but-moved resolution routed to the auto-fix lane with the corrected line.
     *
     * @param question      the question asked.
     * @param evidence      the curator-verifiable justification.
     * @param correctedLine the line at which the target actually resolves.
     * @return a {@link Outcome#PRESENT} resolution in {@link Lane#AUTO_FIX} carrying the corrected line.
     */
    public static Resolution autoFix(final String question, final String evidence, final int correctedLine) {
        return new Resolution(Outcome.PRESENT, Lane.AUTO_FIX, question, evidence, correctedLine, null, null);
    }

    /**
     * A present-but-line-cited resolution that migrates a {@code File.java:NN} reference to
     * {@code File.java#symbol}: line NN is a declaration, so the reference should name the symbol and drop
     * the volatile line. Routed to the auto-fix lane carrying the symbol name.
     *
     * @param question the question asked.
     * @param evidence the curator-verifiable justification.
     * @param symbol   the declaration's name the reference migrates to.
     * @return a {@link Outcome#PRESENT} resolution in {@link Lane#AUTO_FIX} carrying the migration symbol.
     */
    public static Resolution autoFixSymbol(final String question, final String evidence, final String symbol) {
        return new Resolution(Outcome.PRESENT, Lane.AUTO_FIX, question, evidence, null, null, symbol);
    }

    /**
     * A package/path-move resolution: the cited source exists at exactly one other location. The finding
     * still asserts (the KB claim is wrong until edited), but the unique resolved path additionally
     * drives a ready path-rewrite proposal in the auto-fix artifact.
     *
     * @param question     the question asked.
     * @param evidence     the curator-verifiable justification.
     * @param resolvedPath the repo-relative path the cited source actually resolves at.
     * @return a {@link Outcome#PRESENT} resolution in {@link Lane#ASSERT} carrying the resolved path.
     */
    public static Resolution moved(final String question, final String evidence, final String resolvedPath) {
        return new Resolution(Outcome.PRESENT, Lane.ASSERT, question, evidence, null, resolvedPath, null);
    }

    /**
     * Whether this resolution produces a finding (as opposed to a clean pass).
     *
     * @return {@code true} when a lane is set.
     */
    public boolean emitsFinding() {
        return lane != null;
    }
}
