// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * An OutputStream that captures data written to System.out while still writing it to the original System.out.
 */
class SystemOutCapturer extends OutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    /**
     * Starts capturing System.out output.
     */
    public void start() {
        System.setOut(new PrintStream(this, true, StandardCharsets.UTF_8));
    }

    /**
     * Stops capturing System.out output and restores the original System.out.
     *
     * @throws IOException if an I/O error occurs
     */
    public void stop() throws IOException {
        buffer.flush();
        buffer.close();
        System.setOut(originalOut);
    }

    /**
     * Retrieves the captured output as a String.
     *
     * @return the captured output
     */
    @NonNull
    public String getCapturedOutput() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) {
        buffer.write(b);
        originalOut.write(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(@NonNull final byte[] b) throws IOException {
        buffer.write(b);
        originalOut.write(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(@NonNull final byte[] b, final int off, final int len) {
        buffer.write(b, off, len);
        originalOut.write(b, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() throws IOException {
        buffer.flush();
        originalOut.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        buffer.close();
        originalOut.close();
    }
}
