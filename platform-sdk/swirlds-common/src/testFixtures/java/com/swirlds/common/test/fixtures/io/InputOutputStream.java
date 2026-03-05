// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io;

import com.swirlds.common.io.streams.DebuggableDataInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A convenience class that constructs a pair of streams.
 */
public class InputOutputStream implements AutoCloseable {
    private final ByteArrayOutputStream outByteStream;
    private final SerializableDataOutputStream outStream;
    private SerializableDataInputStream inStream;

    /**
     * Create an input/output stream pair.
     */
    public InputOutputStream() {
        outByteStream = new ByteArrayOutputStream();
        outStream = new SerializableDataOutputStream(outByteStream);
    }

    public SerializableDataOutputStream getOutput() {
        return outStream;
    }

    public void startReading() throws IOException {
        startReading(false, false);
    }

    /**
     * Start reading from the stream. No bytes should be written to the output after this is called.
     *
     * @param printBytes
     * 		if true then print the bytes in the stream
     * @param debug
     * 		if true then enable stream debugging
     */
    public void startReading(final boolean printBytes, final boolean debug) throws IOException {
        outByteStream.flush();
        byte[] bytes = outByteStream.toByteArray();
        if (printBytes) {
            System.out.println(Arrays.toString(bytes));
        }

        if (debug) {
            inStream = new DebuggableDataInputStream(new ByteArrayInputStream(bytes));
        } else {
            inStream = new SerializableDataInputStream(new ByteArrayInputStream(bytes));
        }

        outByteStream.close();
        outStream.close();
    }

    public void printBytes() {
        System.out.println(Arrays.toString(outByteStream.toByteArray()));
    }

    public SerializableDataInputStream getInput() {
        return inStream;
    }

    public void close() throws IOException {
        if (inStream != null) {
            inStream.close();
        }

        outStream.close();
        outByteStream.close();
    }
}
