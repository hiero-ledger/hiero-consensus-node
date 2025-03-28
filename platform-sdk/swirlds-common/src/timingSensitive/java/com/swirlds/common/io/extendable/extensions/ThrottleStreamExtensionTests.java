// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions;

import static com.swirlds.common.io.extendable.ExtendableInputStream.extendInputStream;
import static com.swirlds.common.io.extendable.ExtendableOutputStream.extendOutputStream;
import static com.swirlds.common.utility.CompareTo.isGreaterThan;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.ExtendableOutputStream;
import com.swirlds.common.test.fixtures.io.extendable.StreamSanityChecks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ThrottleStreamExtension Tests")
class ThrottleStreamExtensionTests {

    @Test
    @DisplayName("Input Stream Sanity Test")
    void inputStreamSanityTest() throws IOException {
        StreamSanityChecks.inputStreamSanityCheck(
                (final InputStream base) -> new ExtendableInputStream(base, new ThrottleStreamExtension(1024 * 1024)));
    }

    @Test
    @DisplayName("Output Stream Sanity Test")
    void outputStreamSanityTest() throws IOException {
        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) ->
                new ExtendableOutputStream(base, new ThrottleStreamExtension(1024 * 1024)));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100})
    @DisplayName("Input Stream Test")
    void inputStreamTest(final int timeIncrementMs) throws IOException {
        final Random random = getRandomPrintSeed();

        final int bytesPerSecond = 1024 * 1024;
        final CountingStreamExtension counter = new CountingStreamExtension();

        final InputStream byteIn = new ByteArrayInputStream(new byte[1024 * 1024 * 10]);
        final InputStream in = extendInputStream(
                byteIn, new ThrottleStreamExtension(bytesPerSecond, Duration.ofMillis(timeIncrementMs)), counter);

        final Instant start = Instant.now();
        final Duration duration = Duration.ofSeconds(1);

        while (true) {
            final Instant now = Instant.now();
            final Duration elapsed = Duration.between(start, now);
            if (isGreaterThan(elapsed, duration)) {
                break;
            }

            if (random.nextBoolean()) {
                for (int i = 0; i < 1024; i++) {
                    in.read();
                }
            }

            if (random.nextBoolean()) {
                in.read(new byte[1024], 0, 1024);
            }

            if (random.nextBoolean()) {
                in.readNBytes(1024);
            }

            if (random.nextBoolean()) {
                in.readNBytes(new byte[1024], 0, 1024);
            }
        }

        final long count = counter.getCount();
        final double ratio = count / (double) bytesPerSecond;

        assertTrue(ratio > 0.8 && ratio < 1.2, "read at " + ratio + " times the expected rate");
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100})
    @DisplayName("Output Stream Test")
    void outputStreamTest(final int timeIncrementMs) throws IOException {
        final Random random = getRandomPrintSeed();

        final int bytesPerSecond = 1024 * 1024;
        final CountingStreamExtension counter = new CountingStreamExtension();

        final OutputStream byteOut = new ByteArrayOutputStream();
        final OutputStream out = extendOutputStream(
                byteOut, new ThrottleStreamExtension(bytesPerSecond, Duration.ofMillis(timeIncrementMs)), counter);

        final Instant start = Instant.now();
        final Duration duration = Duration.ofSeconds(1);

        while (true) {
            final Instant now = Instant.now();
            final Duration elapsed = Duration.between(start, now);
            if (isGreaterThan(elapsed, duration)) {
                break;
            }

            if (random.nextBoolean()) {
                for (int i = 0; i < 1024; i++) {
                    out.write(i);
                }
            }

            if (random.nextBoolean()) {
                out.write(new byte[1024], 0, 1024);
            }
        }

        final long count = counter.getCount();
        final double ratio = count / (double) bytesPerSecond;

        assertTrue(ratio > 0.8 && ratio < 1.2, "read at " + ratio + " times the expected rate");
    }
}
