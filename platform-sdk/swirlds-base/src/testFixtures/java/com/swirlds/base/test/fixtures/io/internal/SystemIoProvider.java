// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io.internal;

import com.swirlds.base.test.fixtures.io.SystemErrProvider;
import com.swirlds.base.test.fixtures.io.SystemOutProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Implementation of {@link SystemOutProvider} and {@link SystemErrProvider}.
 */
public class SystemIoProvider implements SystemOutProvider, SystemErrProvider {

    private final StringBuilder internalBuilder = new StringBuilder();

    private boolean readAll = false;

    private final ByteArrayOutputStream outputStream;

    /**
     * Constructs a new instance.
     *
     * @param outputStream the output stream to read from
     */
    public SystemIoProvider(@NonNull final ByteArrayOutputStream outputStream) {
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
    }

    /**
     * Reads all lines from the output stream and stores them in the internal builder.
     */
    private void readAll() {
        try {
            internalBuilder.setLength(0); // clear the builder
            internalBuilder.append(outputStream.toString());
        } catch (Exception e) {
            throw new RuntimeException("Error reading from outputStream", e);
        } finally {
            readAll = true;
        }
    }

    @Override
    public Stream<String> getLines() {
        if (!readAll) {
            readAll();
        }
        return internalBuilder.toString().lines();
    }
}
