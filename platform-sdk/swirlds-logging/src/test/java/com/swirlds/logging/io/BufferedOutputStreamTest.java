// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class BufferedOutputStreamTest {

    @Test
    void testWriteSingleByte() throws IOException {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 10)) {
            // When
            bufferedOutputStream.write('A');
        }

        // Then
        assertThat(outputStream.toString()).isEqualTo("A");
    }

    @Test
    void testWriteByteArray() throws IOException {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] bytes = "Hello, Swirlds!".getBytes(StandardCharsets.UTF_8);
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 10)) {
            // When
            bufferedOutputStream.write(bytes);
        }

        // Then
        assertThat(outputStream.toString()).isEqualTo("Hello, Swirlds!");
    }

    @Test
    void testWriteByteArrayWithOffsetAndLength() throws IOException {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] bytes = "Hello, Swirlds!".getBytes(StandardCharsets.UTF_8);
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 10)) {
            // When
            bufferedOutputStream.write(bytes, 7, 7);
        }

        // Then
        assertThat(outputStream.toString()).isEqualTo("Swirlds");
    }

    @Test
    void testFlush() throws IOException {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 10)) {

            // When
            bufferedOutputStream.write('A');
            bufferedOutputStream.flush();

            // Then
            assertThat(outputStream.toString()).isEqualTo("A");
        }
    }

    @Test
    void testWriteNoFlush() throws IOException {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 1); ) {

            // When / Then
            bufferedOutputStream.write('A');
            assertThat(outputStream.size()).isEqualTo(0);
            bufferedOutputStream.write('B');
            assertThat(outputStream.size()).isEqualTo(2);
            assertThat(outputStream.toString()).isEqualTo("AB");
        }
    }

    @Test
    void testWriteNoFlushArray() throws IOException {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 7); ) {

            // When / Then
            final byte[] a = "Hello ".getBytes(StandardCharsets.UTF_8);
            final byte[] b = "Swirlds!".getBytes(StandardCharsets.UTF_8);
            bufferedOutputStream.write(a);
            assertThat(outputStream.size()).isEqualTo(0);
            bufferedOutputStream.write(b);
            assertThat(outputStream.size()).isEqualTo(14);
            assertThat(outputStream.toString()).isEqualTo("Hello Swirlds!");
        }
    }

    @Test
    void testClose() throws IOException {
        // Given
        final AtomicBoolean underlyingCloseWasCalled = new AtomicBoolean();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream() {
            // Small hack so we don't add mockito dependency
            @Override
            public void close() throws IOException {
                super.close();
                underlyingCloseWasCalled.set(true);
            }
        };
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, 10);

        // When
        bufferedOutputStream.close();

        // Then
        assertThat(outputStream.toString()).isEmpty();
        assertTrue(underlyingCloseWasCalled.get());
    }

    @Test
    void testInvalidBufferCapacity() {
        // Given
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // When / Then
        assertThrows(IllegalArgumentException.class, () -> new BufferedOutputStream(outputStream, 0));
    }
}
