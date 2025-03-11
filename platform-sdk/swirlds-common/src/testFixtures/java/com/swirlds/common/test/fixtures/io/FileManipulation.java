// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Misc utilities for manipulating files.
 */
public final class FileManipulation {

    private FileManipulation() {}

    /**
     * Truncate a file in place. Resulting file will only have the specified number of bytes.
     *
     * @param file
     * 		the file to be truncated
     * @param resultingSize
     * 		the desired size of the file after being truncated, in bytes
     */
    public static void truncateFile(final Path file, final int resultingSize) throws IOException {
        // Grab the raw bytes.
        final InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()));
        final byte[] bytes = in.readAllBytes();
        in.close();

        if (bytes.length < resultingSize) {
            throw new IllegalArgumentException("Cannot truncate a file to a size larger than its current size");
        }

        Files.delete(file);

        writeSomeOfTheseBytes(file, bytes, resultingSize);
    }

    /**
     * Similar to {@link #truncateFile(Path, int)}, but instead of truncating the file to a specific size, it
     * removes a specified number of bytes from the end of the file.
     *
     * @param file
     * 		the file that should be modified
     * @param bytesToRemove
     * 		the byte count to remove from the end of the file
     */
    public static void truncateNBytesFromFile(final Path file, final int bytesToRemove) throws IOException {
        // Grab the raw bytes.
        final InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()));
        final byte[] bytes = in.readAllBytes();
        in.close();

        Files.delete(file);

        final int resultingSize = Math.max(0, bytes.length - bytesToRemove);
        writeSomeOfTheseBytes(file, bytes, resultingSize);
    }

    /**
     * Write bytes to a file.
     *
     * @param file
     * 		the file to write to
     * @param bytes
     * 		the bytes to write, may exceed the number actually written
     * @param numberOfBytesToWrite
     * 		the number of bytes to write
     */
    private static void writeSomeOfTheseBytes(final Path file, final byte[] bytes, final int numberOfBytesToWrite)
            throws IOException {
        final byte[] truncatedBytes = new byte[numberOfBytesToWrite];
        System.arraycopy(bytes, 0, truncatedBytes, 0, truncatedBytes.length);
        final OutputStream out = new BufferedOutputStream(new FileOutputStream(file.toFile()));
        out.write(truncatedBytes);
        out.close();
    }

    /**
     * Write a random file of the given size.
     *
     * @param random
     * 		a source of randomness
     * @param path
     * 		the path of the file to write to
     * @param size
     * 		the number of bytes to write
     */
    public static void writeRandomBytes(final Random random, final Path path, final int size) throws IOException {
        if (!Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        final byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        final OutputStream out = new BufferedOutputStream(new FileOutputStream(path.toFile()));
        out.write(bytes);
        out.close();
    }

    /**
     * Corrupt a file in place.
     *
     * @param random
     * 		a source of randomness
     * @param file
     * 		the file to be corrupted
     * @param corruptionStartIndex
     * 		all data starting at this index will be corrupted
     */
    public static void corruptFile(final Random random, final Path file, final int corruptionStartIndex)
            throws IOException {

        // Grab the raw bytes.
        final InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()));
        final byte[] bytes = in.readAllBytes();
        in.close();

        if (bytes.length < corruptionStartIndex) {
            throw new IllegalArgumentException("Requested corruption index exceeds size of file");
        }

        Files.delete(file);

        final byte[] corruptedBytes = new byte[bytes.length - corruptionStartIndex];
        random.nextBytes(corruptedBytes);
        System.arraycopy(corruptedBytes, 0, bytes, corruptionStartIndex, corruptedBytes.length);

        final OutputStream out = new BufferedOutputStream(new FileOutputStream(file.toFile()));
        out.write(bytes);
        out.close();
    }
}
