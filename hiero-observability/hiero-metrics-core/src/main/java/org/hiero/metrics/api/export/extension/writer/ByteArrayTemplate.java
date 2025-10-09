// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A template for byte array with placeholders that can be replaced with variable byte arrays.
 * <p>
 * Template can be constructed using builder pattern ({@link #builder()}), allowing to append fixed byte arrays and add
 * placeholders. When iterating over the template, the placeholders are replaced with the provided
 * variable byte arrays.
 * <p>
 * Internally the template is represented as an array of byte array chunks, where each chunk is either
 * a fixed/static byte array or a {@code null} representing a placeholder.
 */
public final class ByteArrayTemplate {

    private final byte[][] chunks;
    private final int placeholdersCount;

    private ByteArrayTemplate(Builder builder) {
        chunks = builder.chunks.toArray(new byte[0][]);
        placeholdersCount = builder.placeholdersCount;
    }

    /**
     * @return a new builder for {@link ByteArrayTemplate}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns an iterator over the byte arrays in the template, replacing placeholders with the provided variable
     * byte arrays.
     *
     * @param variables the variable byte arrays to replace the placeholders
     * @return an iterator over the byte arrays in the template
     * @throws IllegalArgumentException if the number of variables does not match the number of placeholders
     */
    public Iterator<byte[]> iterator(final byte[]... variables) {
        return iterator(variables.length, variables);
    }

    /**
     * Returns an iterator over the byte arrays in the template, replacing placeholders with the provided variable
     * byte arrays.
     * <p>
     * This is similar to {@link #iterator(byte[]...)}, but allows to specify the number of variables explicitly.
     * Could be used when the variables are provided in a larger array and only a prefix of the array should be used.
     *
     * @param varsCount the number of variable byte arrays to replace the placeholders
     * @param variables the variable byte arrays to replace the placeholders
     * @return an iterator over the byte arrays in the template
     * @throws IllegalArgumentException if the number of variables does not match the number of placeholders
     */
    public Iterator<byte[]> iterator(int varsCount, final byte[]... variables) {
        if (varsCount > variables.length) {
            throw new IllegalArgumentException(
                    "Vars count is greater than variables array length: " + varsCount + " > " + variables.length);
        }
        if (varsCount != placeholdersCount) {
            throw new IllegalArgumentException("Number of variables is not equal to number of placeholders: expected "
                    + placeholdersCount + " but got " + varsCount);
        }

        return new Iterator<>() {

            private int chunkIdx = 0;
            private int variableIdx = 0;

            @Override
            public boolean hasNext() {
                return chunkIdx < chunks.length;
            }

            @Override
            public byte[] next() {
                final byte[] chunk = chunks[chunkIdx++];
                if (chunk == null) {
                    return variables[variableIdx++];
                } else {
                    return chunk;
                }
            }
        };
    }

    /**
     * A builder for {@link ByteArrayTemplate}.
     */
    public static final class Builder {

        private final List<byte[]> chunks = new ArrayList<>();

        private final UnsynchronizedByteArrayOutputStream buffer = new UnsynchronizedByteArrayOutputStream(256);
        private int placeholdersCount = 0;

        /**
         * Appends a string to the current chunk using UTF-8 encoding.
         *
         * @param data the string to append
         * @return this builder
         */
        public Builder appendUtf8(String data) {
            return append(data.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Appends a single byte to the current fixes byte array chunk.
         *
         * @param b the byte to append
         * @return this builder
         */
        public Builder append(byte b) {
            buffer.write(b);
            return this;
        }

        /**
         * Appends a byte array to the current fixes byte array chunk.
         *
         * @param data the byte array to append
         * @return this builder
         */
        public Builder append(byte[] data) {
            buffer.write(data);
            return this;
        }

        /**
         * Finalizes the current fixed byte array chunk (if any) and adds a placeholder for a variable byte array.
         *
         * @return this builder
         */
        public Builder addPlaceholder() {
            finalizeBuilderChunk();
            chunks.add(null); // placeholder
            placeholdersCount++;
            return this;
        }

        private void finalizeBuilderChunk() {
            if (buffer.isEmpty()) {
                return;
            }
            chunks.add(buffer.toByteArray());
            buffer.reset();
        }

        /**
         * Builds the {@link ByteArrayTemplate} from the appended fixed byte arrays and placeholders.
         *
         * @return the built {@link ByteArrayTemplate}
         */
        public ByteArrayTemplate build() {
            finalizeBuilderChunk();
            return new ByteArrayTemplate(this);
        }
    }
}
