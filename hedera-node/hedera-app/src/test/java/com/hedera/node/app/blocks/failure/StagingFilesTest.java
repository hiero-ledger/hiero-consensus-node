// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
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

    @Test
    void atomicGzipCopyWritesGzippedDestinationAndLeavesNoTempBehind(@TempDir final Path dir) throws IOException {
        final Path src = Files.writeString(dir.resolve("7.pnd.json"), "{\"block\":7}");
        final Path dest = dir.resolve(StagingFiles.stagedSidecarName(src));

        StagingFiles.atomicGzipCopy(src, dest);

        assertThat(dest.getFileName().toString()).isEqualTo("7.pnd.json.gz");
        try (final InputStream in = new GZIPInputStream(Files.newInputStream(dest))) {
            assertThat(new String(in.readAllBytes())).isEqualTo("{\"block\":7}");
        }
        assertThat(StagingFiles.tmpFor(dest)).doesNotExist();
    }

    @Test
    void writeUploadMarkersMarksEveryGzFileOnly(@TempDir final Path dir) throws IOException {
        Files.write(dir.resolve("7.blk.gz"), new byte[] {1});
        Files.write(dir.resolve("8.pnd.json.gz"), new byte[] {2});
        Files.write(dir.resolve("9.open.gz.tmp"), new byte[] {3});

        StagingFiles.writeUploadMarkers(dir);
        // Idempotent: re-marking an already marked dir is harmless.
        StagingFiles.writeUploadMarkers(dir);

        assertThat(dir.resolve("7.blk.mf")).exists().isEmptyFile();
        assertThat(dir.resolve("8.pnd.json.mf")).exists().isEmptyFile();
        assertThat(dir.resolve("9.open.mf")).doesNotExist();
        assertThat(dir.resolve("9.open.gz.mf")).doesNotExist();
    }

    @Test
    void writeUploadMarkersToleratesAMissingDir(@TempDir final Path dir) {
        assertThatCode(() -> StagingFiles.writeUploadMarkers(dir.resolve("missing")))
                .doesNotThrowAnyException();
    }
}
