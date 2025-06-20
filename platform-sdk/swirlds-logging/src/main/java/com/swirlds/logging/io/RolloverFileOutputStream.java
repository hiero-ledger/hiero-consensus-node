// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.io;

import static com.swirlds.logging.utils.StringUtils.toPaddedDigitsString;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@link RolloverFileOutputStream} is an {@link OutputStream} implementation that supports rollover size  functionality
 * for the underlying file. Note: It's important to manage the lifecycle of the output stream properly by calling
 * {@link #flush()} and {@link #close()} methods when finished using it to ensure proper resource cleanup.
 */
public class RolloverFileOutputStream extends OutputStream {
    protected final Path logPath;
    private final int maxFiles;
    private final long maxFileSize;
    private final boolean append;
    private long remainingSize;
    private FileOutputStream outputStream;
    private final String baseName;
    private final String extension;
    private final int indexLength;
    private int index;

    /**
     * Creates an {@link RolloverFileOutputStream}. Supporting size based rollover. Usage:
     * <pre>
     *     // Example usage with size-based rollover
     *     Path logPath = Paths.get("logs", "example.log");
     *     long maxFileSize = 1024 * 1024; // 1 MB
     *     boolean append = true;
     *     int maxFiles = 5; // Maximum number of rolling files
     *     RolloverFileOutputStream outputStream = new RolloverFileOutputStream(logPath, maxFileSize, append, maxFiles);
     * </pre>
     *
     * @param logPath     path where the logging file is located. Should contain the base dir + the name of the logging
     *                    file.
     * @param maxFileSize maximum size for the file. The limit is checked with best effort
     * @param append      if true and the file exists, appends the content to the file. if not, the file is rolled.
     * @param maxFiles Within a rolling period, how many rolling files are allowed.
     */
    protected RolloverFileOutputStream(
            final @NonNull Path logPath, final long maxFileSize, final boolean append, final int maxFiles) {
        this.logPath = logPath.toAbsolutePath().getParent();
        this.append = append;
        this.maxFiles = maxFiles;
        this.maxFileSize = maxFileSize;
        final String baseFile = logPath.getFileName().toString();
        final int lastDotIndex = baseFile.lastIndexOf(".");

        if (lastDotIndex > 0) {
            this.baseName = baseFile.substring(0, lastDotIndex);
            this.extension = baseFile.substring(lastDotIndex + 1);
        } else {
            this.baseName = baseFile;
            this.extension = "";
        }

        this.index = 0;
        this.indexLength = String.valueOf(maxFiles).length();
        try {
            this.outputStream = new FileOutputStream(logPath.toString(), this.append);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Log file cannot be created", e);
        }

        if (Files.exists(logPath) && append) {
            this.remainingSize = maxFileSize - logPath.toFile().length();
        } else {
            this.remainingSize = maxFileSize;
            this.roll();
        }
    }

    /**
     * Writes {@code length} bytes from the specified {@code bytes} array starting at {@code offset} off to this file
     * output stream. Rolls over the file if necessary.
     */
    @Override
    public synchronized void write(@NonNull final byte[] bytes, final int offset, final int length) throws IOException {
        ((OutputStream) outputStream).write(bytes, offset, length);
        this.remainingSize -= length;
        this.rollIfNeeded();
    }

    /**
     * Writes a byte array into the stream. Rolls over the file if necessary
     */
    @Override
    public synchronized void write(@NonNull final byte[] bytes) throws IOException {
        ((OutputStream) outputStream).write(bytes);
        this.remainingSize -= (bytes.length);
        this.rollIfNeeded();
    }

    /**
     * Writes a single byte to the stream. Rolls over the file if necessary
     */
    @Override
    public synchronized void write(final int b) throws IOException {
        ((OutputStream) outputStream).write(b);
        this.remainingSize--;
        this.rollIfNeeded();
    }

    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }

    @Override
    public void close() throws IOException {
        outputStream.flush();
        ((OutputStream) outputStream).close();
    }

    private String dotExtension() {
        if (extension.isEmpty()) {
            return "";
        }
        return "." + extension;
    }

    private void rollIfNeeded() {
        if (remainingSize <= 0) {
            this.roll();
        }
    }

    private String logFileName() {
        return baseName + this.dotExtension();
    }

    protected Path logFilePath() {
        return logPath.resolve(logFileName());
    }

    /**
     * Rolls the file
     */
    private void roll() {
        final long maxIndex = maxFiles - 1;
        int currentIndex = index;
        Path newPath = getPathFor(currentIndex);

        // Start by searching a new index if current index is in use
        while (Files.exists(newPath) && currentIndex <= maxIndex) {
            currentIndex++;
            newPath = getPathFor(currentIndex % maxFiles);
        }

        if (currentIndex > maxIndex) {
            currentIndex = index;
            newPath = getPathFor(currentIndex % maxFiles);
        }

        final File file = logFilePath().toFile();
        try {
            outputStream.close();
            Files.move(file.toPath(), newPath, StandardCopyOption.REPLACE_EXISTING);
            outputStream = new FileOutputStream(file, append);
        } catch (IOException e) {
            throw new IllegalStateException("Something happened while rolling over", e);
        }
        index = currentIndex;
        remainingSize = maxFileSize;
    }

    private Path getPathFor(int index) {
        return logPath.resolve(baseName + "." + toPaddedDigitsString(index, indexLength) + dotExtension());
    }
}
