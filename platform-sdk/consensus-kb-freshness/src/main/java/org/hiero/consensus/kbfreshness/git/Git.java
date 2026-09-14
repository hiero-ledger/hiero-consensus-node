// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A thin, read-only wrapper over the {@code git} CLI for freshness comparisons. Output is a function
 * of committed history, so results are deterministic for a given checkout. Degrades gracefully: if
 * git is unavailable or a path is untracked, lookups return {@code null} and callers treat the
 * freshness as unknown rather than asserting anything.
 */
public final class Git {

    /** Absolute, normalized repository root against which git commands run. */
    private final Path repoRoot;
    /** Whether a working git checkout was detected at construction time. */
    private final boolean available;
    /** Per-run cache of repo-relative path to its last commit date (or {@code null} if none). */
    private final Map<String, String> lastCommitDateCache = new HashMap<>();
    /** Per-run cache of a gone repo-relative path to the path it was renamed to (or {@code null}). */
    private final Map<String, String> renameCache = new HashMap<>();
    /** Per-run cache of a pathspec to the short hash and subject of the commit that deleted it. */
    private final Map<String, String> deletionCache = new HashMap<>();

    /**
     * Creates a wrapper rooted at the given repository and probes whether git is usable.
     *
     * @param repoRoot the repository root; resolved to an absolute, normalized path.
     */
    public Git(final Path repoRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.available = probe();
    }

    /**
     * Whether a working git checkout was detected.
     *
     * @return {@code true} if git commands can be run against this repository.
     */
    public boolean available() {
        return available;
    }

    /** The committer date (YYYY-MM-DD) of the most recent commit touching {@code repoRelPath}, or null. */
    public String lastCommitDate(final String repoRelPath) {
        if (!available) {
            return null;
        }
        return lastCommitDateCache.computeIfAbsent(repoRelPath, p -> {
            final String out = run(List.of("git", "log", "-1", "--format=%cs", "--", p));
            return out == null || out.isBlank() ? null : out.strip();
        });
    }

    /**
     * The committer date (YYYY-MM-DD) of the current {@code HEAD} commit — the checkout a run was
     * performed against. Used as the {@code last_reviewed} date for documents that anchor no source
     * (there is no per-source commit to derive from, but the review still happened against this commit).
     *
     * @return the {@code HEAD} committer date, or {@code null} if git is unavailable or the repo has no
     *     commits.
     */
    public String headCommitDate() {
        if (!available) {
            return null;
        }
        final String out = run(List.of("git", "log", "-1", "--format=%cs"));
        return out == null || out.isBlank() ? null : out.strip();
    }

    /**
     * The path a now-gone file was most recently renamed to, if git can trace it and the target still
     * exists. Follows the cited path's history for rename ({@code R}) commits; the newest one names the
     * current location. Returns {@code null} when git is unavailable, no rename is recorded, or the traced
     * target no longer exists.
     *
     * @param repoRelPath the gone repo-relative path to trace.
     * @return the repo-relative path it was renamed to, or {@code null}.
     */
    public String findRename(final String repoRelPath) {
        if (!available) {
            return null;
        }
        return renameCache.computeIfAbsent(repoRelPath, path -> {
            final String out = run(List.of(
                    "git",
                    "log",
                    "--follow",
                    "--find-renames",
                    "--diff-filter=R",
                    "--name-status",
                    "--format=",
                    "--",
                    path));
            if (out == null || out.isBlank()) {
                return null;
            }
            // Newest first; a rename line is "R<score>\t<old>\t<new>". The first names the current location.
            for (final String line : out.split("\n")) {
                if (line.startsWith("R")) {
                    final String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                        final String newPath = parts[2].strip();
                        if (!newPath.isEmpty() && Files.isRegularFile(repoRoot.resolve(newPath))) {
                            return newPath;
                        }
                    }
                }
            }
            return null;
        });
    }

    /**
     * The short hash and subject of the most recent commit that deleted a file matching the pathspec
     * (git wildcard syntax; a plain repo-relative path also works). Returns {@code null} when git is
     * unavailable or no deletion is recorded.
     *
     * @param pathspec the git pathspec to trace (e.g. a repo-relative path, or {@code *&#47;File.java}).
     * @return {@code "<short-hash> <subject>"} of the deleting commit, or {@code null}.
     */
    public String findDeletion(final String pathspec) {
        if (!available) {
            return null;
        }
        return deletionCache.computeIfAbsent(pathspec, ps -> {
            final String out = run(List.of("git", "log", "--diff-filter=D", "-1", "--format=%h %s", "--", ps));
            return out == null || out.isBlank() ? null : out.strip();
        });
    }

    /**
     * Probes whether the repository root is inside a git work tree.
     *
     * @return {@code true} if git reports this directory is inside a working tree.
     */
    private boolean probe() {
        return run(List.of("git", "rev-parse", "--is-inside-work-tree")) != null;
    }

    /**
     * Runs a git command in the repository root and captures its standard output.
     *
     * @param command the command and its arguments.
     * @return the captured stdout on a zero exit code, or {@code null} on any failure, timeout, or
     *     non-zero exit.
     */
    private String run(final List<String> command) {
        try {
            final Process process = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            final String output;
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                final StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString();
            }
            final boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (final IOException e) {
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
