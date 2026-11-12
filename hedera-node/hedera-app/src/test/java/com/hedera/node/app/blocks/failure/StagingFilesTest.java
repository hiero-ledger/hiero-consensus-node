// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingFilesTest {

    @Test
    void tmpForAppendsTmpSuffixAsSibling(@TempDir final Path dir) {
        final Path dest = dir.resolve("0000000000000000123.iss.zstd");
        assertThat(StagingFiles.tmpFor(dest)).isEqualTo(dir.resolve("0000000000000000123.iss.zstd.tmp"));
    }

    @Test
    void atomicCopyWritesDestinationAndLeavesNoTempBehind(@TempDir final Path dir) throws IOException {
        final Path src = Files.writeString(dir.resolve("src.zstd"), "hello");
        final Path dest = dir.resolve("sub").resolve("dest.zstd");
        Files.createDirectories(dest.getParent());

        StagingFiles.atomicCopy(src, dest);

        assertThat(dest).exists();
        assertThat(Files.readString(dest)).isEqualTo("hello");
        // The transient .tmp sibling (which the uploader's extension filter ignores) must not linger after success.
        assertThat(StagingFiles.tmpFor(dest)).doesNotExist();
    }

    @Test
    void atomicCopyReplacesAnExistingDestination(@TempDir final Path dir) throws IOException {
        final Path src = Files.writeString(dir.resolve("src.zstd"), "new");
        final Path dest = Files.writeString(dir.resolve("dest.zstd"), "old");

        StagingFiles.atomicCopy(src, dest);

        assertThat(Files.readString(dest)).isEqualTo("new");
    }

    @Test
    void atomicMoveOntoMovesTmpAndRemovesIt(@TempDir final Path dir) throws IOException {
        final Path dest = dir.resolve("dest.zstd");
        final Path tmp = StagingFiles.tmpFor(dest);
        Files.writeString(tmp, "payload");

        StagingFiles.atomicMoveOnto(tmp, dest);

        assertThat(tmp).doesNotExist();
        assertThat(Files.readString(dest)).isEqualTo("payload");
    }

    @Test
    void atomicZstdCopyWritesCompressedDestinationAndLeavesNoTempBehind(@TempDir final Path dir) throws IOException {
        final Path src = Files.writeString(dir.resolve("7.pnd.json"), "{\"block\":7}");
        final Path dest = dir.resolve(StagingFiles.stagedSidecarName(src));

        StagingFiles.atomicZstdCopy(src, dest);

        assertThat(dest.getFileName().toString()).isEqualTo("7.pnd.json.zstd");
        try (final InputStream in = BlockFileUtils.openInputStream(dest)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("{\"block\":7}");
        }
        assertThat(StagingFiles.tmpFor(dest)).doesNotExist();
    }

    @Test
    void stageContentsCopiesAZstdFileAsIs(@TempDir final Path dir) throws IOException {
        final Path src = Files.write(dir.resolve("0000000000000000007.blk.zstd"), new byte[] {1, 2, 3});
        final Path stageDir = Files.createDirectories(dir.resolve("stage"));

        final Path staged = StagingFiles.stageContents(src, stageDir);

        assertThat(staged).isEqualTo(stageDir.resolve("0000000000000000007.blk.zstd"));
        assertThat(Files.readAllBytes(staged)).containsExactly(1, 2, 3);
        assertThat(StagingFiles.tmpFor(staged)).doesNotExist();
    }

    @Test
    void stageContentsWrapsALegacyGzipFile(@TempDir final Path dir) throws IOException {
        final String legacyName = "000000000000000000000000000000000007.blk.gz";
        final Path src = Files.write(dir.resolve(legacyName), new byte[] {1, 2, 3});
        final Path stageDir = Files.createDirectories(dir.resolve("stage"));

        final Path staged = StagingFiles.stageContents(src, stageDir);

        // Every staged file must end in .zstd for the uploader, so the legacy file is wrapped, bytes untouched.
        assertThat(staged).isEqualTo(stageDir.resolve(legacyName + ".zstd"));
        try (final InputStream in = BlockFileUtils.openInputStream(staged)) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThat(stageDir.resolve(legacyName)).doesNotExist();
        assertThat(StagingFiles.tmpFor(staged)).doesNotExist();
    }

    @Test
    void writeUploadMarkersMarksEveryZstdFileOnly(@TempDir final Path dir) throws IOException {
        Files.write(dir.resolve("7.blk.zstd"), new byte[] {1});
        Files.write(dir.resolve("8.pnd.json.zstd"), new byte[] {2});
        Files.write(dir.resolve("9.open.zstd.tmp"), new byte[] {3});
        Files.write(dir.resolve("5.blk.gz"), new byte[] {4});

        StagingFiles.writeUploadMarkers(dir);
        // Idempotent: re-marking an already marked dir is harmless.
        StagingFiles.writeUploadMarkers(dir);

        assertThat(dir.resolve("7.blk.mf")).exists().isEmptyFile();
        assertThat(dir.resolve("8.pnd.json.mf")).exists().isEmptyFile();
        assertThat(dir.resolve("9.open.mf")).doesNotExist();
        assertThat(dir.resolve("9.open.zstd.mf")).doesNotExist();
        // The uploader is set to the .zstd extension only, so any other file gets no marker.
        assertThat(dir.resolve("5.blk.mf")).doesNotExist();
    }

    @Test
    void writeUploadMarkersToleratesAMissingDir(@TempDir final Path dir) {
        assertThatCode(() -> StagingFiles.writeUploadMarkers(dir.resolve("missing")))
                .doesNotThrowAnyException();
    }
}
