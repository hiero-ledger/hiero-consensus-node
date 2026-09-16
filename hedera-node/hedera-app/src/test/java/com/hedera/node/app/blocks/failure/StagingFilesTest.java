// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingFilesTest {

    @Test
    void tmpForAppendsTmpSuffixAsSibling(@TempDir final Path dir) {
        final Path dest = dir.resolve("000000000000000000000000000000000123.iss.gz");
        assertThat(StagingFiles.tmpFor(dest)).isEqualTo(dir.resolve("000000000000000000000000000000000123.iss.gz.tmp"));
    }

    @Test
    void atomicCopyWritesDestinationAndLeavesNoTempBehind(@TempDir final Path dir) throws IOException {
        final Path src = Files.writeString(dir.resolve("src.gz"), "hello");
        final Path dest = dir.resolve("sub").resolve("dest.gz");
        Files.createDirectories(dest.getParent());

        StagingFiles.atomicCopy(src, dest);

        assertThat(dest).exists();
        assertThat(Files.readString(dest)).isEqualTo("hello");
        // The transient .tmp sibling (which the uploader's extension filter ignores) must not linger after success.
        assertThat(StagingFiles.tmpFor(dest)).doesNotExist();
    }

    @Test
    void atomicCopyReplacesAnExistingDestination(@TempDir final Path dir) throws IOException {
        final Path src = Files.writeString(dir.resolve("src.gz"), "new");
        final Path dest = Files.writeString(dir.resolve("dest.gz"), "old");

        StagingFiles.atomicCopy(src, dest);

        assertThat(Files.readString(dest)).isEqualTo("new");
    }

    @Test
    void atomicMoveOntoMovesTmpAndRemovesIt(@TempDir final Path dir) throws IOException {
        final Path dest = dir.resolve("dest.gz");
        final Path tmp = StagingFiles.tmpFor(dest);
        Files.writeString(tmp, "payload");

        StagingFiles.atomicMoveOnto(tmp, dest);

        assertThat(tmp).doesNotExist();
        assertThat(Files.readString(dest)).isEqualTo("payload");
    }
}
