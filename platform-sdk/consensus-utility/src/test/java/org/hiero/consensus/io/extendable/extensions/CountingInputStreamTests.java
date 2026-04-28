// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.extendable.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.io.extendable.StreamSanityChecks;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.io.counting.ByteCounter;
import org.hiero.consensus.io.counting.CounterType;
import org.hiero.consensus.io.counting.CountingInputStream;
import org.hiero.consensus.io.counting.CountingOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("InputStreamExtension Tests")
class CountingInputStreamTests {

    @Test
    @DisplayName("Input Stream Sanity Test")
    void inputStreamSanityTest() throws IOException {
        StreamSanityChecks.inputStreamSanityCheck(
                (final InputStream base) -> new CountingInputStream(base, CounterType.THREAD_SAFE));

        StreamSanityChecks.inputStreamSanityCheck(
                (final InputStream base) -> new CountingInputStream(base, CounterType.FAST));
    }

    @Test
    @DisplayName("Output Stream Sanity Test")
    void outputStreamSanityTest() throws IOException {
        StreamSanityChecks.outputStreamSanityCheck(
                (final OutputStream base) -> new CountingOutputStream(base, CounterType.THREAD_SAFE));

        StreamSanityChecks.outputStreamSanityCheck(
                (final OutputStream base) -> new CountingOutputStream(base, CounterType.FAST));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Counting Test")
    void countingTest() throws IOException {
        final PipedInputStream pipeIn = new PipedInputStream();
        final PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

        final CountingInputStream countIn = new CountingInputStream(pipeIn, CounterType.THREAD_SAFE);
        final ByteCounter byteCounterIn = countIn.byteCounter();
        final CountingOutputStream countOut = new CountingOutputStream(pipeOut, CounterType.THREAD_SAFE);
        final ByteCounter byteCounterOut = countOut.byteCounter();

        assertEquals(0, byteCounterIn.getCount(), "no bytes have been read");
        assertEquals(0, byteCounterOut.getCount(), "no bytes have been written");

        final int writeBytes = 10;
        final int readBytes = writeBytes / 2;

        countOut.write(new byte[writeBytes]);

        assertEquals(0, byteCounterIn.getCount(), "no bytes have been read");
        assertEquals(writeBytes, byteCounterOut.getCount(), "incorrect count");

        countIn.read(new byte[readBytes]);

        assertEquals(readBytes, byteCounterIn.getCount(), "incorrect count");
        assertEquals(writeBytes, byteCounterOut.getCount(), "incorrect count");

        countIn.read(new byte[readBytes * 2]);
        assertEquals(writeBytes, byteCounterIn.getCount(), "incorrect count");
        assertEquals(writeBytes, byteCounterOut.getCount(), "incorrect count");

        byteCounterIn.getAndReset();
        assertEquals(0, byteCounterIn.getCount(), "no additional bytes written");

        countIn.close();
        countOut.close();
    }

    @Test
    @DisplayName("Reading Closed Stream Test")
    void readingClosedStreamTest() throws IOException {
        final byte[] bytes = new byte[1024];
        final ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
        final CountingInputStream in = new CountingInputStream(byteIn, CounterType.THREAD_SAFE);

        in.readNBytes(bytes.length);

        assertEquals(bytes.length, in.byteCounter().getCount(), "count should have all bytes counted");

        // Read some bytes from the now closed stream
        in.read();
        in.readNBytes(new byte[10], 0, 10);
        in.readNBytes(10);
        in.readNBytes(new byte[10], 0, 10);

        assertEquals(bytes.length, in.byteCounter().getCount(), "count should not have changed");
    }
}
