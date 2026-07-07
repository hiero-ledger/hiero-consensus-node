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
import java.util.stream.Stream;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ParsedFile;

/**
 * A deterministic index of hand-written Java sources under the configured module roots (by default
 * {@code platform-sdk} and {@code hedera-node}). It maps each file's basename to the repo-relative
 * paths declaring it — enough to tell "gone" from "moved to another module" — and offers filesystem
 * existence checks plus a cached parse-only view of any file. No compilation, no network.
 */
public final class SourceIndex {

    /** Absolute, normalized repository root. */
    private final Path repoRoot;
    /** File basename to the sorted repo-relative paths declaring it. */
    private final Map<String, List<String>> basenameToPaths;
    /** Per-run cache of repo-relative path to its parsed view. */
    private final Map<String, ParsedFile> parseCache = new HashMap<>();

    /**
     * Creates an index over the given repository with the precomputed basename map.
     *
     * @param repoRoot        the absolute, normalized repository root.
     * @param basenameToPaths file basename to the repo-relative paths declaring it.
     */
    private SourceIndex(final Path repoRoot, final Map<String, List<String>> basenameToPaths) {
        this.repoRoot = repoRoot;
        this.basenameToPaths = basenameToPaths;
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
        for (final String moduleRoot : moduleRoots) {
            final Path start = root.resolve(moduleRoot);
            if (!Files.isDirectory(start)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                        .forEach(p -> {
                            final String basename = p.getFileName().toString();
                            final String rel = root.relativize(p).toString().replace('\\', '/');
                            map.computeIfAbsent(basename, k -> new ArrayList<>())
                                    .add(rel);
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
        return new SourceIndex(root, sorted);
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
     * Parses a repo-relative source file, caching the result for the run.
     *
     * @param repoRelPath the repo-relative source path.
     * @return the parsed view of the file.
     */
    public ParsedFile parse(final String repoRelPath) {
        return parseCache.computeIfAbsent(repoRelPath, p -> JavaParsing.parse(repoRoot.resolve(p)));
    }
}
