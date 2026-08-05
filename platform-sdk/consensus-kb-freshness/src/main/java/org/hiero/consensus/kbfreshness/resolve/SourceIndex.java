// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ParsedFile;

/**
 * A deterministic index of hand-written Java sources under the configured module roots (by default
 * {@code platform-sdk} and {@code hedera-node}). It maps each file's basename to the repo-relative
 * paths declaring it — enough to tell "gone" from "moved to another module" — records every package
 * holding an indexed source (for the prose package/FQN checks), and offers filesystem existence
 * checks plus a cached parse-only view of any file. No compilation, no network.
 */
public final class SourceIndex {

    /** The path segment separating a module root from the package tree of its main sources. */
    private static final String MAIN_SOURCE_TREE = "/src/main/java/";

    /** Absolute, normalized repository root. */
    private final Path repoRoot;
    /** File basename to the sorted repo-relative paths declaring it. */
    private final Map<String, List<String>> basenameToPaths;
    /** Every dotted package under an indexed {@code src/main/java} tree, sorted. */
    private final NavigableSet<String> packages;
    /** Per-run cache of repo-relative path to its parsed view. */
    private final Map<String, ParsedFile> parseCache = new HashMap<>();

    /**
     * Creates an index over the given repository with the precomputed basename map and package set.
     *
     * @param repoRoot        the absolute, normalized repository root.
     * @param basenameToPaths file basename to the repo-relative paths declaring it.
     * @param packages        every dotted package containing an indexed source file.
     */
    private SourceIndex(
            final Path repoRoot, final Map<String, List<String>> basenameToPaths, final NavigableSet<String> packages) {
        this.repoRoot = repoRoot;
        this.basenameToPaths = basenameToPaths;
        this.packages = packages;
    }

    /**
     * Builds the index by walking the given repo-relative module roots for {@code *.java} files under
     * a {@code src/main/java} tree.
     *
     * @param repoRoot    the repository root; resolved to an absolute, normalized path.
     * @param moduleRoots the repo-relative module roots to scan.
     * @return a deterministic index over the discovered sources.
     * @throws UncheckedIOException if walking a module root fails.
     */
    public static SourceIndex build(final Path repoRoot, final List<String> moduleRoots) {
        final Path root = repoRoot.toAbsolutePath().normalize();
        final Map<String, List<String>> map = new HashMap<>();
        final NavigableSet<String> packages = new TreeSet<>();
        for (final String moduleRoot : moduleRoots) {
            final Path start = root.resolve(moduleRoot);
            if (!Files.isDirectory(start)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> p.toString().replace('\\', '/').contains(MAIN_SOURCE_TREE))
                        .forEach(p -> {
                            final String basename = p.getFileName().toString();
                            final String rel = root.relativize(p).toString().replace('\\', '/');
                            map.computeIfAbsent(basename, k -> new ArrayList<>())
                                    .add(rel);
                            final String pkg = dottedPackageOf(rel);
                            if (pkg != null) {
                                packages.add(pkg);
                            }
                        });
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to index sources under " + start, e);
            }
        }
        // Sort for determinism.
        final Map<String, List<String>> sorted = new LinkedHashMap<>();
        map.keySet().stream().sorted().forEach(k -> {
            final List<String> paths = map.get(k);
            paths.sort(Comparator.naturalOrder());
            sorted.put(k, List.copyOf(paths));
        });
        return new SourceIndex(root, sorted, packages);
    }

    /**
     * The dotted package of a repo-relative source path: the directories between {@code src/main/java/}
     * and the file name. Shared with the renderers so a moved citation's old and new packages are
     * derived the same way the index derives them.
     *
     * @param repoRelPath the repo-relative, forward-slashed source path.
     * @return the dotted package, or {@code null} when the path has no main-source tree or the file
     *     sits in the default package.
     */
    public static String dottedPackageOf(final String repoRelPath) {
        final int tree = repoRelPath.indexOf(MAIN_SOURCE_TREE);
        final int lastSlash = repoRelPath.lastIndexOf('/');
        final int pkgStart = tree + MAIN_SOURCE_TREE.length();
        if (tree < 0 || lastSlash <= pkgStart) {
            return null;
        }
        return repoRelPath.substring(pkgStart, lastSlash).replace('/', '.');
    }

    /**
     * The repository root this index was built against.
     *
     * @return the absolute, normalized repository root.
     */
    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * Whether a repo-relative path is an existing regular file.
     *
     * @param repoRelPath the repo-relative path.
     * @return {@code true} if the path resolves to a regular file.
     */
    public boolean fileExists(final String repoRelPath) {
        return Files.isRegularFile(repoRoot.resolve(repoRelPath));
    }

    /**
     * Whether a repo-relative path is an existing directory.
     *
     * @param repoRelPath the repo-relative path.
     * @return {@code true} if the path resolves to a directory.
     */
    public boolean dirExists(final String repoRelPath) {
        return Files.isDirectory(repoRoot.resolve(repoRelPath));
    }

    /**
     * Repo-relative paths of every indexed file with the given basename (e.g. {@code Foo.java}).
     *
     * @param basename the file basename to look up.
     * @return the sorted repo-relative paths, or an empty list if none are indexed.
     */
    public List<String> pathsForBasename(final String basename) {
        return basenameToPaths.getOrDefault(basename, List.of());
    }

    /**
     * Every indexed file basename, in sorted order. Drives near-name matching for gone sources and the
     * config-record scan.
     *
     * @return the sorted set of indexed basenames.
     */
    public java.util.Set<String> basenames() {
        return java.util.Collections.unmodifiableSet(basenameToPaths.keySet());
    }

    /**
     * Whether a dotted Java package exists under an indexed {@code src/main/java} tree. A package
     * "exists" when an indexed source file lives in it or in one of its subpackages — a parent package
     * with no files of its own is still a real package.
     *
     * @param dottedPackage the dotted package name (e.g. {@code com.swirlds.platform.wiring}).
     * @return {@code true} when the package (or a subpackage of it) contains an indexed source file.
     */
    public boolean packageExists(final String dottedPackage) {
        final String ceiling = packages.ceiling(dottedPackage);
        return ceiling != null && (ceiling.equals(dottedPackage) || ceiling.startsWith(dottedPackage + "."));
    }

    /**
     * Parses a repo-relative source file, caching the result for the run.
     *
     * @param repoRelPath the repo-relative source path.
     * @return the parsed view of the file.
     */
    public ParsedFile parse(final String repoRelPath) {
        return parseCache.computeIfAbsent(repoRelPath, p -> JavaParsing.parse(repoRoot.resolve(p)));
    }
}
