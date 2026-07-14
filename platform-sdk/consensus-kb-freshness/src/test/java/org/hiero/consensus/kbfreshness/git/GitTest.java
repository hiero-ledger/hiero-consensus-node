// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for the git-history lookups, against a throwaway repository created in a temp
 * directory. Skips (rather than fails) when no usable git binary is on the path.
 */
class GitTest {

    @TempDir
    Path tmp;

    @Test
    void findDeletionNamesTheDeletingCommit() throws Exception {
        assumeTrue(runGit("init"), "git unavailable");
        Files.createDirectories(tmp.resolve("m/src/main/java"));
        Files.writeString(tmp.resolve("m/src/main/java/Gone.java"), "class Gone {}");
        assumeTrue(runGit("add", "."), "git add failed");
        assumeTrue(runGit("commit", "-m", "add Gone"), "git commit failed");
        assumeTrue(runGit("rm", "m/src/main/java/Gone.java"), "git rm failed");
        assumeTrue(runGit("commit", "-m", "delete Gone"), "git commit failed");

        final Git git = new Git(tmp);
        assumeTrue(git.available(), "git probe failed");
        assertThat(git.findDeletion("m/src/main/java/Gone.java")).contains("delete Gone");
        assertThat(git.findDeletion("*/Gone.java")).contains("delete Gone");
        assertThat(git.findDeletion("*/NeverExisted.java")).isNull();
    }

    /**
     * Runs a git command in the temp repository with a hermetic identity, reporting success.
     *
     * @param args the git subcommand and its arguments.
     * @return {@code true} when the command exited zero.
     */
    private boolean runGit(final String... args) {
        final List<String> command = new ArrayList<>(List.of(
                "git",
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "init.defaultBranch=main"));
        command.addAll(List.of(args));
        try {
            final Process process = new ProcessBuilder(command)
                    .directory(tmp.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (final IOException e) {
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
