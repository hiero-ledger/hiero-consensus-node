// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * A codec for encoding and decoding the {@link QueueState} used in on-disk queue implementations.
 *
 * <p><b>Where is it used?</b></p>
 * This class is used within on-disk queue implementations to encode and decode queue states as they
 * are written to or read from persistent storage.
 * For example: The {@link com.swirlds.state.merkle.disk.OnDiskQueueHelper} uses it to persist and retrieve
 * the {@link QueueState} for defining boundaries and metadata of a queue.
 */
public class QueueStateCodec implements Codec<QueueState> {

    /**
     * Singleton instance of {@code QueueCodec}.
     */
    public static final Codec<QueueState> INSTANCE = new QueueStateCodec();

    /**
     * {@inheritDoc}
     *
     * <p>Parses the input to create a {@link QueueState}.
     */
    @NonNull
    @Override
    public QueueState parse(
            @NonNull ReadableSequentialData input, boolean strictMode, boolean parseUnknownFields, int maxDepth)
            throws ParseException {
        return new QueueState(input);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes the {@link QueueState} to the output stream.
     */
    @Override
    public void write(@NonNull QueueState item, @NonNull WritableSequentialData output) throws IOException {
        item.writeTo(output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Measures the size (in bytes) of a serialized {@link QueueState} by computing
     * the difference in stream positions before and after parsing.
     */
    @Override
    public int measure(@NonNull ReadableSequentialData input) throws ParseException {
        final var start = input.position();
        parse(input);
        final var end = input.position();
        return (int) (end - start);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int measureRecord(QueueState item) {
        return item.getSizeInBytes();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Compares the given {@link QueueState} with its serialized binary representation.
     */
    @Override
    public boolean fastEquals(@NonNull QueueState item, @NonNull ReadableSequentialData input) throws ParseException {
        return item.equals(parse(input));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link QueueState} with default head and tail indices.
     */
    @Override
    public QueueState getDefaultInstance() {
        return new QueueState();
    }
}
