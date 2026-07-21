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
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@code --fix}: applying the certain auto-fix edits to a throwaway copy of the
 * fixture repo must rewrite exactly the cited line/path, resolve the finding on a re-run, and be a no-op
 * the second time (idempotent).
 */
class AutoFixApplierTest {

    @TempDir
    private Path tmp;

    @Test
    void appliesCertainFixesResolvesThemAndIsIdempotent() throws Exception {
        final Path repo = tmp.resolve("repo");
        copyTree(fixtureRepo(), repo);
        final Path kb = repo.resolve("platform-sdk/docs/consensus-layer");
        final RunConfig config = new RunConfig(repo, kb, null, List.of("platform-sdk"), Allowlist.withDefaults(), "");

        final RunResult before = new Engine(config).run();
        assertThat(hasMovedClassPathFinding(before))
                .as("MovedClass is a path-move assert before fixing")
                .isTrue();

        final AutoFixApplier.Result applied = AutoFixApplier.apply(before, repo);
        assertThat(applied.applied()).isPositive();
        assertThat(applied.skipped()).isZero();
        assertThat(applied.filesChanged()).isNotEmpty();

        // The RUL-001 components: entry was rewritten from module-a to the module the class resolves in.
        final String rul = Files.readString(kb.resolve("rules/RUL-001-fixture.md"));
        assertThat(rul).contains("module-b/src/main/java/com/y/MovedClass.java");
        assertThat(rul).doesNotContain("module-a/src/main/java/com/y/MovedClass.java");

        // The moved method line reference (WithMethod::foo) was corrected :3 → :6.
        final String topic = Files.readString(kb.resolve("architecture/topics/present-topic.md"));
        assertThat(topic).contains("WithMethod.java:6").doesNotContain("WithMethod.java:3");

        // Re-running sees the citations as correct — the path move no longer asserts.
        final RunResult after = new Engine(config).run();
        assertThat(hasMovedClassPathFinding(after)).isFalse();

        // Nothing left to apply.
        assertThat(AutoFixApplier.apply(after, repo).applied()).isZero();
    }

    private static boolean hasMovedClassPathFinding(final RunResult result) {
        return result.findings().stream()
                .anyMatch(f -> f.kind() == AnchorKind.SOURCE_PATH
                        && f.target().endsWith("com/y/MovedClass.java")
                        && f.resolvedPath() != null);
    }

    private static Path fixtureRepo() throws Exception {
        return Path.of(AutoFixApplierTest.class.getResource("/fixtures/repo").toURI());
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
