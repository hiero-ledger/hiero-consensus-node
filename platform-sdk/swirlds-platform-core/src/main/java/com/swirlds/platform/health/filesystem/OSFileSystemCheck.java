// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health.filesystem;

import static com.swirlds.platform.health.OSHealthCheckUtils.timeSupplier;

import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import com.swirlds.platform.health.OSHealthCheckUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Checks that the operating system is able to open a file and read a byte from it without throwing an exception or
 * timing out.
 */
public final class OSFileSystemCheck {

    /**
     * The default number of milliseconds to wait for the file check to complete before timing out.
     */
    private static final long DEFAULT_READ_TIMEOUT_MILLIS = 20;

    private OSFileSystemCheck() {}

    /**
     * Checks that the first byte of a file can be read in a certain amount of time.
     *
     * @param fileToRead
     * 		the file to read. Must contain at least 1 byte of data.
     * @return the file system check report
     * @throws InterruptedException
     * 		if this thread is interrupted while waiting for the file read to complete
     */
    public static Report execute(final Path fileToRead) throws InterruptedException {
        return execute(fileToRead, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /**
     * Checks that the first byte of a file can be read in a certain amount of time.
     *
     * @param fileToRead
     * 		the file to read. Must contain at least 1 byte of data.
     * @param timeoutMillis
     * 		the maximum number of milliseconds to wait for the first byte of a file to be read
     * @return the file system check report
     * @throws InterruptedException
     * 		if this thread is interrupted while waiting for the file read to complete
     */
    public static Report execute(final Path fileToRead, final long timeoutMillis) throws InterruptedException {
        if (!Files.exists(fileToRead)) {
            return Report.failure(TestResultCode.FILE_DOES_NOT_EXIST);
        }

        if (Files.isDirectory(fileToRead)) {
            return Report.failure(TestResultCode.FILE_IS_DIRECTORY);
        }

        if (!Files.isReadable(fileToRead)) {
            return Report.failure(TestResultCode.FILE_NOT_READABLE);
        }

        final AtomicReference<Report> failureReport = new AtomicReference<>();

        // Open and read the first byte of the file
        final Supplier<Byte> randomRequester = () -> {
            try (final InputStream in = Files.newInputStream(fileToRead);
                    final CountingStreamExtension counter = new CountingStreamExtension();
                    final InputStream ein = new ExtendableInputStream(in, counter)) {
                final byte byteRead = (byte) ein.read();

                if (counter.getCount() < 1) {
                    failureReport.set(Report.failure(TestResultCode.FILE_EMPTY));
                }
                return byteRead;
            } catch (final IOException e) {
                failureReport.set(Report.failure(TestResultCode.EXCEPTION, e));
            }
            return null;
        };

        final OSHealthCheckUtils.SupplierResult<Byte> result = timeSupplier(randomRequester, timeoutMillis);

        // If the report is non-null, the test failed. Return that report.
        if (failureReport.get() != null) {
            return failureReport.get();
        }

        if (result == null) {
            return Report.failure(TestResultCode.TIMED_OUT);
        }

        final long elapsedNanos = result.duration().toNanos();
        final Byte byteRead = result.result();
        return Report.success(elapsedNanos, byteRead);
    }

    /**
     * Contains data about the OS's ability to read data from a file.
     *
     * @param code
     * 		the test result code
     * @param readNanos
     * 		the number of nanoseconds it took to open and read the first byte of a file, or {@code null} if the test
     * 		failed
     * @param data
     * 		the first byte of the provided file, or {@code null} if the file could not be read
     * @param exception
     * 		if the check resulted in an exception, the exception that was thrown. Otherwise, {@code null}
     */
    public record Report(TestResultCode code, Long readNanos, Integer data, Exception exception) {

        private static final String NAME = "File System Check";

        public static Report failure(final TestResultCode code) {
            return new Report(code, null, null, null);
        }

        public static Report failure(final TestResultCode code, final Exception e) {
            return new Report(code, null, null, e);
        }

        public static Report success(final long readNanos, int byteRead) {
            return new Report(TestResultCode.SUCCESS, readNanos, byteRead, null);
        }

        /**
         * @return the name of the check this report applies to
         */
        public static String name() {
            return NAME;
        }
    }

    public enum TestResultCode {
        /** The test succeeded */
        SUCCESS,
        /** The file to read does not exist */
        FILE_DOES_NOT_EXIST,
        /** The file provided is a directory */
        FILE_IS_DIRECTORY,
        /** The file provided is not readable */
        FILE_NOT_READABLE,
        /** The read timed out */
        TIMED_OUT,
        /** The file provided was empty */
        FILE_EMPTY,
        /** An exception was thrown while reading */
        EXCEPTION
    }
}
