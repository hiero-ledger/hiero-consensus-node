// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.ExtendableOutputStream;
import com.swirlds.common.test.fixtures.io.extendable.StreamSanityChecks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ExpiringStreamExtension Tests")
class ExpiringStreamExtensionTests {

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 1000})
    @DisplayName("Input Stream Sanity Test")
    void inputStreamSanityTest(final int bytesPerSample) throws IOException {
        StreamSanityChecks.inputStreamSanityCheck((final InputStream base) ->
                new ExtendableInputStream(base, new ExpiringStreamExtension(Duration.ofHours(1), 100)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 1000})
    @DisplayName("Output Stream Sanity Test")
    void outputStreamSanityTest() throws IOException {
        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) ->
                new ExtendableOutputStream(base, new ExpiringStreamExtension(Duration.ofHours(1), 100)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 1000})
    @DisplayName("Input Stream Test")
    void inputStreamTest(final int bytesPerSample) throws IOException, InterruptedException {

        final int size = 1024 * 1024;

        final ByteArrayInputStream byteIn = new ByteArrayInputStream(new byte[size]);
        final ExpiringStreamExtension extension = new ExpiringStreamExtension(Duration.ofMillis(100), bytesPerSample);
        final InputStream in = new ExtendableInputStream(byteIn, extension);

        // Reading bytes should not throw exceptions yet
        for (int i = 0; i < bytesPerSample; i++) {
            in.read();
        }
        in.readNBytes(new byte[bytesPerSample], 0, bytesPerSample);
        in.readNBytes(bytesPerSample);
        in.readNBytes(new byte[bytesPerSample], 0, bytesPerSample);

        MILLISECONDS.sleep(200);

        // Now, reading bytes will throw exceptions
        assertThrows(
                IOException.class,
                () -> {
                    for (int i = 0; i < bytesPerSample + 1; i++) {
                        in.read();
                    }
                },
                "stream is expired, read should throw");
        assertThrows(
                IOException.class,
                () -> in.readNBytes(new byte[bytesPerSample], 0, bytesPerSample),
                "stream is expired, read should throw");
        assertThrows(IOException.class, () -> in.readNBytes(bytesPerSample), "stream is expired, read should throw");
        assertThrows(
                IOException.class,
                () -> in.readNBytes(new byte[bytesPerSample], 0, bytesPerSample),
                "stream is expired, read should throw");
        assertThrows(IOException.class, () -> in.read(), "stream is expired, read should throw");

        extension.reset();

        // Reading bytes should not throw exceptions after reset
        for (int i = 0; i < bytesPerSample; i++) {
            in.read();
        }
        in.readNBytes(new byte[bytesPerSample], 0, bytesPerSample);
        in.readNBytes(bytesPerSample);
        in.readNBytes(new byte[bytesPerSample], 0, bytesPerSample);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 1000})
    @DisplayName("Output Stream Test")
    void outputStreamTest(final int bytesPerSample) throws InterruptedException, IOException {
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final ExpiringStreamExtension extension = new ExpiringStreamExtension(Duration.ofMillis(100), bytesPerSample);
        final OutputStream out = new ExtendableOutputStream(byteOut, extension);

        // Writing bytes should not throw exceptions yet
        for (int i = 0; i < bytesPerSample; i++) {
            out.write(i);
        }
        out.write(new byte[bytesPerSample], 0, bytesPerSample);

        MILLISECONDS.sleep(200);

        // Now, writing bytes will throw exceptions
        assertThrows(
                IOException.class,
                () -> {
                    for (int i = 0; i < bytesPerSample + 1; i++) {
                        out.write(i);
                    }
                },
                "stream is expired, write should throw");
        assertThrows(
                IOException.class,
                () -> out.write(new byte[bytesPerSample], 0, bytesPerSample),
                "stream is expired, write should throw");
        assertThrows(IOException.class, () -> out.write(1), "stream is expired, write should throw");

        extension.reset();

        // Writing bytes should not throw exceptions after reset
        for (int i = 0; i < bytesPerSample; i++) {
            out.write(i);
        }
        out.write(new byte[bytesPerSample], 0, bytesPerSample);
    }
}
