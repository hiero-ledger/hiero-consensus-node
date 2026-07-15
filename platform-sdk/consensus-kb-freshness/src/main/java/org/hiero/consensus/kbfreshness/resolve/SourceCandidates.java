// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * The shared anchor→indexed-path resolution rules, used by both {@link AnchorResolver} and the semantic
 * worklist so the two cannot drift: an abbreviated {@code module/.../File.java} citation resolves by
 * basename within its cited module, a fully-qualified type citation by package plus simple name, and a
 * stale citation whose basename resolves at exactly one other indexed path is a unique package/path move.
 * Callers apply their own reduction to whatever these return.
 */
public final class SourceCandidates {

    /** Prevents instantiation of this static-only helper. */
    private SourceCandidates() {}

    /**
     * The indexed paths of a fully-qualified type citation that live in the cited package: every indexed
     * file of the type's basename whose path ends in the cited package. Empty when the type is nowhere in
     * that package (a caller then treats it as gone, or falls back to {@link #uniqueMove}).
     *
     * @param index      the source index to query.
     * @param fqn        the fully-qualified type name.
     * @param simpleName the primary (file-defining) type's simple name.
     * @return the in-package indexed paths, in the index's sorted order (possibly empty).
     */
    public static List<String> forFqn(final SourceIndex index, final String fqn, final String simpleName) {
        final String basename = simpleName + ".java";
        final String pkgPath =
                fqn.substring(0, fqn.lastIndexOf("." + simpleName)).replace('.', '/');
        final List<String> resolved = new ArrayList<>();
        for (final String p : index.pathsForBasename(basename)) {
            if (p.endsWith("/" + pkgPath + "/" + basename)) {
                resolved.add(p);
            }
        }
        return resolved;
    }

    /**
     * The indexed paths of a basename scoped to a cited module: every indexed file of that basename whose
     * module matches {@code citedModule}, or all of them when no module was cited. This is the abbreviated
     * {@code module/.../File.java} resolution rule.
     *
     * @param index       the source index to query.
     * @param basename    the cited file basename (e.g. {@code Foo.java}).
     * @param citedModule the module the citation scoped to, or {@code null} for no scope.
     * @return the matching indexed paths, in the index's sorted order (possibly empty).
     */
    public static List<String> inModule(final SourceIndex index, final String basename, final String citedModule) {
        final List<String> resolved = new ArrayList<>();
        for (final String p : index.pathsForBasename(basename)) {
            if (citedModule == null || citedModule.equals(RepoPaths.moduleOf(p))) {
                resolved.add(p);
            }
        }
        return resolved;
    }

    /**
     * The single indexed path of a basename whose cited location is stale — the package/path-move signal —
     * or an empty list when the basename is gone or resolves ambiguously (more than one candidate). This is
     * the unique-move rule shared by the resolver's move target and the worklist's moved-anchor tracking.
     *
     * @param index    the source index to query.
     * @param basename the cited file basename.
     * @return a singleton list with the unique moved-to path, or an empty list.
     */
    public static List<String> uniqueMove(final SourceIndex index, final String basename) {
        final List<String> candidates = index.pathsForBasename(basename);
        return candidates.size() == 1 ? List.of(candidates.get(0)) : List.of();
    }
}
