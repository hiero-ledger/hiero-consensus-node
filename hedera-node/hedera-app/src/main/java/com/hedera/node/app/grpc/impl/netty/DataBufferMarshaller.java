// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.MethodDescriptor;
import java.io.InputStream;
import java.util.function.Supplier;

/**
 * A thread-safe implementation of a gRPC marshaller which does nothing but pass through byte arrays as {@link
 * BufferedData}s. A single implementation of this class is designed to be used by multiple threads,
 * including by multiple app instances within a single JVM!
 */
/*@ThreadSafe*/
final class DataBufferMarshaller implements MethodDescriptor.Marshaller<BufferedData> {
    private final int tooBigMessageSize;
    private final Supplier<BufferedData> bufferSupplier;

    /** Constructs a new {@link DataBufferMarshaller}. Only called by {@link GrpcServiceBuilder}. */
    DataBufferMarshaller(final int maxMessageSize, @NonNull final Supplier<BufferedData> bufferSupplier) {
        this.tooBigMessageSize = maxMessageSize + 1;
        this.bufferSupplier = requireNonNull(bufferSupplier);
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public InputStream stream(@NonNull final BufferedData buffer) {
        // KnownLengthStream is a simple wrapper over the byte buffer. We use it because it offers a
        // better performance profile with the gRPC server than a normal InputBuffer.
        return new KnownLengthStream(buffer);
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public BufferedData parse(@NonNull final InputStream stream) {
        // NOTE: Any runtime exception thrown by this method appears correct by inspection
        // of the Google protobuf implementation.
        requireNonNull(stream);

        // Each thread has a single buffer instance that gets reused over and over.
        final var buffer = bufferSupplier.get();
        buffer.reset();

        // We sized the buffer to be 1 byte larger than the MAX_MESSAGE_SIZE.
        // If we have filled the buffer, it means the message had too many bytes,
        // and we will therefore reject it in MethodBase. We reject it there instead of here
        // because if we throw an exception here, Helidon will log a stack trace, which we don't
        // want to do for bad input from the user. Also note that if the user sent us way too many
        // bytes, this method will only read up to TOO_BIG_MESSAGE_SIZE, so there is no risk of
        // the user overwhelming the server with a huge message.
        buffer.writeBytes(stream, tooBigMessageSize);

        // We read some bytes into the buffer, so reset the position and limit accordingly to
        // prepare for reading the data
        buffer.flip();
        return buffer;
    }
}
