// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.MethodDescriptor;
import io.helidon.grpc.core.MarshallerSupplier;
import java.io.IOException;
import java.io.InputStream;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.codec.PublishStreamRequestProtoCodec;
import org.hiero.block.api.codec.PublishStreamResponseProtoCodec;

public class RequestResponseMarshaller<T> implements MethodDescriptor.Marshaller<T> {
    private final Codec<T> codec;

    RequestResponseMarshaller(@NonNull final Class<T> clazz) {
        requireNonNull(clazz);

        if (clazz == PublishStreamRequest.class) {
            this.codec = (Codec<T>) new PublishStreamRequestProtoCodec();
        } else if (clazz == PublishStreamResponse.class) {
            this.codec = (Codec<T>) new PublishStreamResponseProtoCodec();
        } else {
            throw new IllegalArgumentException("Unsupported class: " + clazz.getName());
        }
    }

    @Override
    public InputStream stream(@NonNull final T obj) {
        requireNonNull(obj);
        return codec.toBytes(obj).toInputStream();
    }

    @Override
    public T parse(@NonNull final InputStream inputStream) {
        requireNonNull(inputStream);

        try {
            return codec.parse(Bytes.wrap(inputStream.readAllBytes()));
        } catch (final ParseException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A {@link MarshallerSupplier} implementation that supplies
     * instances of {@link RequestResponseMarshaller}.
     */
    public static class Supplier implements MarshallerSupplier {
        @Override
        public <T> MethodDescriptor.Marshaller<T> get(@NonNull final Class<T> clazz) {
            return new RequestResponseMarshaller<>(clazz);
        }
    }
}
