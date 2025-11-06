// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class UnsynchronizedByteArrayOutputStreamTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, -10, -100})
    void testNegativeCapacity(int capacity) {
        assertThatThrownBy(() -> new UnsynchronizedByteArrayOutputStream(capacity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Capacity must not be negative");
    }

    @Test
    void testEmpty() {
        UnsynchronizedByteArrayOutputStream stream = new UnsynchronizedByteArrayOutputStream();

        verifyIsEmpty(stream);

        stream.write(new byte[0]);
        stream.write(new byte[0], 0, 0);
        stream.writeUtf8("");

        verifyIsEmpty(stream);
    }

    @Test
    void testToByArray() {
        UnsynchronizedByteArrayOutputStream stream = new UnsynchronizedByteArrayOutputStream();
        byte[] data1 = {1, 2, 3};
        stream.write(data1);

        byte[] snapshot1 = stream.toByteArray();
        byte[] snapshot2 = stream.toByteArray();

        assertThat(snapshot1).isEqualTo(data1);

        // test that the returned byte arrays are equal but not the same instance
        assertThat(snapshot1).isEqualTo(data1);
        assertThat(snapshot2).isEqualTo(data1);
        assertThat(snapshot1).isNotSameAs(snapshot2);

        // test that modifying the returned array does not affect the stream's internal state
        snapshot1[0] = 100;
        assertThat(stream.toByteArray()).isEqualTo(data1);

        // test that additional write to the stream does not affect previously taken snapshots
        byte[] data2 = {10, 11, 12};
        stream.write(data2);
        assertThat(snapshot2).isEqualTo(data1);
    }

    @Test
    void testToString() {
        UnsynchronizedByteArrayOutputStream stream = new UnsynchronizedByteArrayOutputStream();
        String testString = "Hello, World!";
        stream.writeUtf8(testString);

        assertThat(stream.toString()).isEqualTo(testString);
    }

    @Test
    void testWriteToOutputStream() throws Exception {
        UnsynchronizedByteArrayOutputStream stream = new UnsynchronizedByteArrayOutputStream();
        String testString = "Hello, OutputStream!";
        stream.writeUtf8(testString);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        stream.writeTo(outputStream);

        assertThat(outputStream.toString(StandardCharsets.UTF_8)).isEqualTo(testString);
    }

    @Test
    void testReset() {
        UnsynchronizedByteArrayOutputStream stream = new UnsynchronizedByteArrayOutputStream();
        stream.writeUtf8("Some data");

        assertThat(stream.isEmpty()).isFalse();
        assertThat(stream.size()).isGreaterThan(0);

        stream.reset();
        verifyIsEmpty(stream);

        stream.writeUtf8("hello");
        assertThat(stream.toString()).isEqualTo("hello");
    }

    @Test
    void testDifferentWritesGrowingCapacity() {
        UnsynchronizedByteArrayOutputStream stream = new UnsynchronizedByteArrayOutputStream(2);

        stream.write((byte) 'A');
        stream.write('B');
        stream.write((byte) 'C'); // should trigger capacity growth

        stream.write(new byte[] {'D', 'E', 'F'});
        stream.write(new byte[] {'K', 'L', 'M', 'O', 'P'}, 1, 3); // write L, M, O

        stream.writeUtf8("XXX XXX XXX "); // should trigger additional capacity growths

        assertThat(stream.size()).isEqualTo(21);
        assertThat(stream.toString()).isEqualTo("ABCDEFLMOXXX XXX XXX ");
    }

    private void verifyIsEmpty(UnsynchronizedByteArrayOutputStream stream) {
        assertThat(stream.isEmpty()).isTrue();
        assertThat(stream.size()).isEqualTo(0);
        assertThat(stream.toString()).isEmpty();
        assertThat(stream.toByteArray()).isEmpty();
    }
}
