// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
                    .redirectErrorStream(false)
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
