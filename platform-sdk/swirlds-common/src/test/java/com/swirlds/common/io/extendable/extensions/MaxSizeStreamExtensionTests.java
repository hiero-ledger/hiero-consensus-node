// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.ExtendableOutputStream;
import com.swirlds.common.test.fixtures.io.extendable.StreamSanityChecks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaxSizeStreamExtension Tests")
class MaxSizeStreamExtensionTests {

    @Test
    @DisplayName("Input Stream Sanity Test")
    void inputStreamSanityTest() throws IOException {
        StreamSanityChecks.inputStreamSanityCheck((final InputStream base) ->
                new ExtendableInputStream(base, new MaxSizeStreamExtension(Long.MAX_VALUE)));

        StreamSanityChecks.inputStreamSanityCheck((final InputStream base) ->
                new ExtendableInputStream(base, new MaxSizeStreamExtension(Long.MAX_VALUE, false)));
    }

    @Test
    @DisplayName("Output Stream Sanity Test")
    void outputStreamSanityTest() throws IOException {
        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) ->
                new ExtendableOutputStream(base, new MaxSizeStreamExtension(Long.MAX_VALUE)));

        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) ->
                new ExtendableOutputStream(base, new MaxSizeStreamExtension(Long.MAX_VALUE, false)));
    }

    @Test
    @DisplayName("Input Stream Test")
    void inputStreamTest() throws IOException {
        final InputStream byteIn = new ByteArrayInputStream(new byte[1024 * 1024]);
        final MaxSizeStreamExtension extension = new MaxSizeStreamExtension(1024);
        final InputStream in = new ExtendableInputStream(byteIn, extension);

        // Read under the limit should not throw
        in.read();
        in.read(new byte[100], 0, 100);
        in.readNBytes(100);
        in.readNBytes(new byte[100], 0, 100);

        // Reading over the limit will cause problems

        extension.reset();
        assertThrows(
                IOException.class,
                () -> {
                    for (int i = 0; i < 1025; i++) {
                        in.read();
                    }
                },
                "too many bytes read");

        extension.reset();
        assertThrows(IOException.class, () -> in.read(new byte[1025], 0, 1025), "too many bytes read");

        extension.reset();
        assertThrows(IOException.class, () -> in.readNBytes(1025), "too many bytes read");

        extension.reset();
        assertThrows(IOException.class, () -> in.readNBytes(new byte[1025], 0, 1025), "too many bytes read");

        extension.reset();

        // After reset reads should work again
        in.read();
        in.read(new byte[100], 0, 100);
        in.readNBytes(100);
        in.readNBytes(new byte[100], 0, 100);
    }

    @Test
    @DisplayName("Output Stream Test")
    void outputStreamTest() throws IOException {
        final OutputStream byteOut = new ByteArrayOutputStream();
        final MaxSizeStreamExtension extension = new MaxSizeStreamExtension(1024);
        final OutputStream out = new ExtendableOutputStream(byteOut, extension);

        // Write under the limit should not throw
        out.write(1);
        out.write(new byte[100], 0, 100);

        // Writing over the limit will cause problems

        extension.reset();
        assertThrows(
                IOException.class,
                () -> {
                    for (int i = 0; i < 1025; i++) {
                        out.write(i);
                    }
                },
                "too many bytes written");

        extension.reset();
        assertThrows(IOException.class, () -> out.write(new byte[1025], 0, 1025), "too many bytes written");

        extension.reset();

        // After reset writes should work again
        out.write(1);
        out.write(new byte[100], 0, 100);
    }
}
