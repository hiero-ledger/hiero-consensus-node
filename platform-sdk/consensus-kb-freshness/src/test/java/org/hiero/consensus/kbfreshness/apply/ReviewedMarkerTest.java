// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.kbfreshness.engine.Engine;
import org.hiero.consensus.kbfreshness.engine.RunConfig;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@code --mark-reviewed}: the marker rewrites exactly the existing {@code last_reviewed:}
 * frontmatter line of an unambiguously named entry, is idempotent, and reports (never guesses) an
 * unknown key, a missing marker line, or a non-ISO date.
 */
class ReviewedMarkerTest {

    @TempDir
    private Path tmp;

    @Test
    void bumpsExistingMarkerReportsProblemsAndIsIdempotent() throws Exception {
        final Path repo = tmp.resolve("repo");
        copyTree(fixtureRepo(), repo);
        final Path kb = repo.resolve("platform-sdk/docs/consensus-layer");
        final RunConfig config = new RunConfig(repo, kb, null, List.of("platform-sdk"), Allowlist.withDefaults(), "");
        final RunResult result = new Engine(config).run();

        // Full key with an explicit date, and a bare slug falling back to the default date.
        final ReviewedMarker.Result marked = ReviewedMarker.apply(
                result, repo, List.of("topic:moved-anchored=2026-07-11", "bare-prose"), "2026-07-12");
        assertThat(marked.problems()).isEmpty();
        assertThat(marked.updated()).isEqualTo(2);
        assertThat(Files.readString(kb.resolve("architecture/topics/moved-anchored.md")))
                .contains("last_reviewed: 2026-07-11")
                .doesNotContain("2020-01-01");
        assertThat(Files.readString(kb.resolve("architecture/topics/bare-prose.md")))
                .contains("last_reviewed: 2026-07-12");

        // Idempotent: the same specs find the dates already current and write nothing.
        final ReviewedMarker.Result again = ReviewedMarker.apply(
                result, repo, List.of("topic:moved-anchored=2026-07-11", "bare-prose=2026-07-12"), "");
        assertThat(again.problems()).isEmpty();
        assertThat(again.updated()).isZero();

        // An unknown key, a doc without the marker line, and a non-ISO date are reported, not guessed.
        final ReviewedMarker.Result problems = ReviewedMarker.apply(
                result,
                repo,
                List.of("topic:no-such-topic=2026-07-11", "RUL-001=2026-07-11", "bare-prose=July 11"),
                "");
        assertThat(problems.updated()).isZero();
        assertThat(problems.problems()).hasSize(3);
        assertThat(problems.problems().get(0)).contains("no scanned entry");
        assertThat(problems.problems().get(1)).contains("no `last_reviewed:` frontmatter line");
        assertThat(problems.problems().get(2)).contains("no ISO yyyy-MM-dd date");
    }

    private static Path fixtureRepo() throws Exception {
        return Path.of(ReviewedMarkerTest.class.getResource("/fixtures/repo").toURI());
    }

    private static void copyTree(final Path src, final Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(p -> {
                final Path target = dst.resolve(src.relativize(p).toString());
                try {
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException("Failed to copy " + p, e);
                }
            });
        }
    }
}
