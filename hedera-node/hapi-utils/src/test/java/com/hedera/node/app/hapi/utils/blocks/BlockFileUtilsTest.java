// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BlockFileUtilsTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"0,0000000000000000000", "1,0000000000000000001", "9223372036854775807,9223372036854775807"})
    void formatsAllArtifactNamesWithNineteenDigits(final long number, final String stem) {
        assertEquals(stem, BlockFileUtils.blockNumberToFileName(number));
        assertEquals(stem + ".blk.zstd", BlockFileUtils.blockFileName(number));
        assertEquals(stem + ".pnd.zstd", BlockFileUtils.pendingFileName(number));
        assertEquals(stem + ".open.zstd", BlockFileUtils.incompleteFileName(number));
        assertEquals(stem + ".mf", BlockFileUtils.markerFileName(number));
        assertEquals(stem + ".pnd.json", BlockFileUtils.pendingProofFileName(number));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void rejectsNegativeBlockNumbers(final long number) {
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.blockNumberToFileName(number));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.blockFileName(number));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.pendingFileName(number));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.incompleteFileName(number));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.markerFileName(number));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.pendingProofFileName(number));
    }

    @ParameterizedTest
    @ValueSource(strings = {".blk.zstd", ".blk.gz", ".blk"})
    void mapsOnlyCompleteSuffixToMarker(final String suffix) {
        final var path = tempDir.resolve("prefix.blk.gz" + suffix);

        assertTrue(BlockFileUtils.isCompleteBlockFile(path));
        assertFalse(BlockFileUtils.isPendingBlockFile(path));
        assertEquals(tempDir.resolve("prefix.blk.gz.mf"), BlockFileUtils.markerPathFor(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {".pnd.zstd", ".pnd.gz", ".pnd"})
    void mapsOnlyPendingSuffixToProof(final String suffix) {
        final var path = tempDir.resolve("prefix.pnd.gz" + suffix);

        assertTrue(BlockFileUtils.isPendingBlockFile(path));
        assertFalse(BlockFileUtils.isCompleteBlockFile(path));
        assertEquals(tempDir.resolve("prefix.pnd.gz.pnd.json"), BlockFileUtils.pendingProofPathFor(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"42.open.zstd", "42.pnd.json", "42.blk.zip", "42.blk.zstd.tmp"})
    void rejectsUnsupportedArtifactSuffixes(final String name) {
        final var path = tempDir.resolve(name);

        assertFalse(BlockFileUtils.isCompleteBlockFile(path));
        assertFalse(BlockFileUtils.isPendingBlockFile(path));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.markerPathFor(path));
        assertThrows(IllegalArgumentException.class, () -> BlockFileUtils.pendingProofPathFor(path));
        assertEquals(Optional.empty(), BlockFileUtils.findBlockFileForMarker(path));
    }

    @Test
    void findsExactStemAndPrefersZstandardThenGzipThenPlain() throws IOException {
        final var marker = tempDir.resolve("0000000000000000042.mf");
        Files.createFile(tempDir.resolve("000000000000000000000000000000000042.blk.zstd"));
        assertEquals(Optional.empty(), BlockFileUtils.findBlockFileForMarker(marker));

        final var plain = Files.createFile(tempDir.resolve("0000000000000000042.blk"));
        assertEquals(Optional.of(plain), BlockFileUtils.findBlockFileForMarker(marker));
        final var gzip = Files.createFile(tempDir.resolve("0000000000000000042.blk.gz"));
        assertEquals(Optional.of(gzip), BlockFileUtils.findBlockFileForMarker(marker));
        final var zstd = Files.createFile(tempDir.resolve("0000000000000000042.blk.zstd"));
        assertEquals(Optional.of(zstd), BlockFileUtils.findBlockFileForMarker(marker));

        Files.delete(zstd);
        Files.createDirectory(zstd);
        assertEquals(Optional.of(gzip), BlockFileUtils.findBlockFileForMarker(marker));
    }

    @Test
    void findsPendingFileForExactProofStemAndPrefersZstandardThenGzipThenPlain() throws IOException {
        final var proof = tempDir.resolve("0000000000000000042.pnd.json");
        Files.createFile(tempDir.resolve("000000000000000000000000000000000042.pnd.zstd"));
        assertEquals(Optional.empty(), BlockFileUtils.findPendingFileForProof(proof));

        final var plain = Files.createFile(tempDir.resolve("0000000000000000042.pnd"));
        assertEquals(Optional.of(plain), BlockFileUtils.findPendingFileForProof(proof));
        final var gzip = Files.createFile(tempDir.resolve("0000000000000000042.pnd.gz"));
        assertEquals(Optional.of(gzip), BlockFileUtils.findPendingFileForProof(proof));
        final var zstd = Files.createFile(tempDir.resolve("0000000000000000042.pnd.zstd"));
        assertEquals(Optional.of(zstd), BlockFileUtils.findPendingFileForProof(proof));

        Files.delete(zstd);
        Files.createDirectory(zstd);
        assertEquals(Optional.of(gzip), BlockFileUtils.findPendingFileForProof(proof));
    }

    @ParameterizedTest
    @ValueSource(strings = {"42.json", "42.pnd.json.tmp", "42.mf", "42.pnd.zstd"})
    void rejectsUnsupportedPendingProofSuffixes(final String name) {
        assertEquals(Optional.empty(), BlockFileUtils.findPendingFileForProof(tempDir.resolve(name)));
    }

    @Test
    void writesStandardZstandardFrameWithChecksum() throws IOException {
        final var payload = new byte[] {1, 2, 3, 4, 5};
        final var frame = compress(payload);
        assertArrayEquals(new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd}, Arrays.copyOf(frame, 4));
        assertEquals(0x04, frame[4] & 0x04, "Zstandard frame content checksum flag");
        final var path = Files.write(tempDir.resolve("42.blk.zstd"), frame);

        try (final var input = BlockFileUtils.openInputStream(path)) {
            assertArrayEquals(payload, input.readAllBytes());
        }
    }

    @Test
    void rejectsCorruptChecksumAndTruncatedZstandardFrames() throws IOException {
        final var frame = compress(new byte[] {1, 2, 3, 4, 5});
        final var corruptChecksum = frame.clone();
        corruptChecksum[corruptChecksum.length - 1] ^= 1;
        final var truncated = Arrays.copyOf(frame, frame.length - 1);

        for (final var invalidFrame : new byte[][] {corruptChecksum, truncated, new byte[] {1, 2, 3, 4}}) {
            final var path = Files.write(tempDir.resolve("42.blk.zstd"), invalidFrame);
            assertThrows(IOException.class, () -> {
                try (final var input = BlockFileUtils.openInputStream(path)) {
                    input.readAllBytes();
                }
            });
        }
    }

    private static byte[] compress(final byte[] payload) throws IOException {
        final var output = new ByteArrayOutputStream();
        try (final var compressed = BlockFileUtils.newZstdOutputStream(output)) {
            compressed.write(payload);
        }
        return output.toByteArray();
    }
}
