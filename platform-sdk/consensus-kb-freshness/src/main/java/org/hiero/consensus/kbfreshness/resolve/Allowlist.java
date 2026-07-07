// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sources outside the hand-written {@code platform-sdk} tree whose symbols the engine cannot
 * authoritatively resolve — PBJ/protoc-generated types, {@code .proto} definitions, and anything
 * under a {@code build/generated} directory. An unresolved citation to such a source is
 * {@code unverifiable}, not {@code absent} (invariant 4): it is routed to the quiet log, never
 * asserted. Built-in defaults can be extended from a file so the list can grow without a code change.
 */
public final class Allowlist {

    /** Path substrings that mark a source as external/generated. */
    private final List<String> pathContains;
    /** Repo-relative path prefixes that mark a source as external/generated. */
    private final List<String> pathPrefixes;
    /** Path suffixes that mark a source as external/generated. */
    private final List<String> pathSuffixes;
    /** Simple type names known to be external/generated regardless of path. */
    private final Set<String> externalNames;

    /**
     * Creates an allowlist from the given matcher collections.
     *
     * @param pathContains  path substrings that mark a source external.
     * @param pathPrefixes  repo-relative path prefixes that mark a source external.
     * @param pathSuffixes  path suffixes that mark a source external.
     * @param externalNames simple type names known to be external.
     */
    private Allowlist(
            final List<String> pathContains,
            final List<String> pathPrefixes,
            final List<String> pathSuffixes,
            final Set<String> externalNames) {
        this.pathContains = pathContains;
        this.pathPrefixes = pathPrefixes;
        this.pathSuffixes = pathSuffixes;
        this.externalNames = externalNames;
    }

    /**
     * Creates an allowlist seeded with the built-in external/generated defaults.
     *
     * @return a new allowlist with the default matchers.
     */
    public static Allowlist withDefaults() {
        final List<String> contains = new ArrayList<>(List.of("/build/generated/"));
        final List<String> prefixes = new ArrayList<>(List.of("hapi/"));
        final List<String> suffixes = new ArrayList<>(List.of(".proto"));
        final Set<String> names = new LinkedHashSet<>(List.of(
                "Roster",
                "RosterEntry",
                "ConsensusSnapshot",
                "SigSet",
                "Signature",
                "GossipSyncData",
                "GossipKnownTips",
                "State"));
        return new Allowlist(contains, prefixes, suffixes, names);
    }

    /**
     * Extends this allowlist with directives from a file's lines. Recognized forms:
     * {@code path-contains:<s>}, {@code path-prefix:<s>}, {@code path-suffix:<s>}, {@code name:<s>}.
     * Blank lines and {@code #} comments are ignored.
     *
     * @param lines the directive lines to parse.
     * @return a new allowlist combining this one with the recognized directives.
     */
    public Allowlist extendedWith(final List<String> lines) {
        final List<String> contains = new ArrayList<>(pathContains);
        final List<String> prefixes = new ArrayList<>(pathPrefixes);
        final List<String> suffixes = new ArrayList<>(pathSuffixes);
        final Set<String> names = new LinkedHashSet<>(externalNames);
        for (final String raw : lines) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String directive = line.substring(0, colon).strip();
            final String value = line.substring(colon + 1).strip();
            switch (directive) {
                case "path-contains" -> contains.add(value);
                case "path-prefix" -> prefixes.add(value);
                case "path-suffix" -> suffixes.add(value);
                case "name" -> names.add(value);
                default -> {
                    // ignore unknown directive
                }
            }
        }
        return new Allowlist(contains, prefixes, suffixes, names);
    }

    /**
     * Whether the given path is external/generated per the path matchers.
     *
     * @param repoRelPath the repo-relative source path (separators normalized internally).
     * @return {@code true} if any contains/prefix/suffix matcher matches the path.
     */
    public boolean isExternalPath(final String repoRelPath) {
        final String p = repoRelPath.replace('\\', '/');
        for (final String c : pathContains) {
            if (p.contains(c)) {
                return true;
            }
        }
        for (final String pre : pathPrefixes) {
            if (p.startsWith(pre)) {
                return true;
            }
        }
        for (final String suf : pathSuffixes) {
            if (p.endsWith(suf)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the given simple type name is an allowlisted external/generated type.
     *
     * @param simpleName the simple (unqualified) type name.
     * @return {@code true} if the name is in the external-names set.
     */
    public boolean isExternalName(final String simpleName) {
        return externalNames.contains(simpleName);
    }
}
