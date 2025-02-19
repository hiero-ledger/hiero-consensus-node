/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.merkle;

import static com.swirlds.common.utility.CommonUtils.getNormalisedStringBytes;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.utility.NonCryptographicHashing;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.memory.InMemoryValue;
import com.swirlds.state.merkle.memory.InMemoryWritableKVState;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.merkle.singleton.ValueLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Utility class for working with states. */
public final class StateUtils {

    private static final Logger logger = LogManager.getLogger(StateUtils.class);

    /** Prevent instantiation */
    private StateUtils() {}

    /**
     * Write the {@code object} to the {@link OutputStream} using the given {@link Codec}.
     *
     * @param out The object to write out
     * @param codec The codec to use. MUST be compatible with the {@code object} type
     * @param object The object to write
     * @return The number of bytes written to the stream.
     * @param <T> The type of the object and associated codec.
     * @throws IOException If the output stream throws it.
     * @throws ClassCastException If the object or codec is not for type {@code T}.
     */
    public static <T> int writeToStream(
            @NonNull final OutputStream out, @NonNull final Codec<T> codec, @Nullable final T object)
            throws IOException {
        final var stream = new WritableStreamingData(out);

        final var byteStream = new ByteArrayOutputStream();
        codec.write(object, new WritableStreamingData(byteStream));

        stream.writeInt(byteStream.size());
        stream.writeBytes(byteStream.toByteArray());
        return byteStream.size();
    }

    /**
     * Read an object from the {@link InputStream} using the given {@link Codec}.
     *
     * @param in The input stream to read from
     * @param codec The codec to use. MUST be compatible with the {@code object} type
     * @return The object read from the stream
     * @param <T> The type of the object and associated codec.
     * @throws IOException If the input stream throws it or parsing fails
     * @throws ClassCastException If the object or codec is not for type {@code T}.
     */
    @Nullable
    public static <T> T readFromStream(@NonNull final InputStream in, @NonNull final Codec<T> codec)
            throws IOException {
        final var stream = new ReadableStreamingData(in);
        final var size = stream.readInt();

        stream.limit((long) size + Integer.BYTES); // +4 for the size
        try {
            return codec.parse(stream);
        } catch (final ParseException ex) {
            throw new IOException(ex);
        }
    }

    /**
     * Verifies the service name meets all the validation requirements.
     *
     * @param serviceName The service name
     * @return The service name provided as the argument
     * @throws NullPointerException if the service name is null
     * @throws IllegalArgumentException if any other validation criteria fails
     */
    @NonNull
    public static String validateServiceName(@NonNull final String serviceName) {
        if (Objects.requireNonNull(serviceName).isEmpty()) {
            throw new IllegalArgumentException("The service name must have characters");
        }

        return validateIdentifier(serviceName);
    }

    /**
     * Verifies the state key meets all the validation requirements.
     *
     * @param stateKey The state key
     * @return The state key provided as the argument
     * @throws NullPointerException if the state key is null
     * @throws IllegalArgumentException if any other validation criteria fails
     */
    @NonNull
    public static String validateStateKey(@NonNull final String stateKey) {
        if (Objects.requireNonNull(stateKey).isEmpty()) {
            throw new IllegalArgumentException("The state key must have characters");
        }

        return validateIdentifier(stateKey);
    }

    /**
     * Verifies the identifier meets all the validation requirements.
     *
     * @param stateKey The identifier
     * @return The identifier provided as the argument
     * @throws NullPointerException if the identifier is null
     * @throws IllegalArgumentException if any other validation criteria fails
     */
    @NonNull
    static String validateIdentifier(@NonNull final String stateKey) {
        if (Objects.requireNonNull(stateKey).isEmpty()) {
            throw new IllegalArgumentException("The identifier must have characters");
        }

        for (int i = 0; i < stateKey.length(); i++) {
            final var c = stateKey.charAt(i);
            if (!isAsciiUnderscoreOrDash(c) && !isAsciiLetter(c) && !isAsciiNumber(c)) {
                throw new IllegalArgumentException("Illegal character '" + c + "' at position " + i);
            }
        }

        return stateKey;
    }

    /**
     * Computes the label for a merkle node given the service name and state key
     *
     * @param serviceName The service name
     * @param stateKey The state key
     * @return The computed label
     */
    public static String computeLabel(@NonNull final String serviceName, @NonNull final String stateKey) {
        return Objects.requireNonNull(serviceName) + "." + Objects.requireNonNull(stateKey);
    }

    /**
     * Given the inputs, compute the corresponding class ID.
     *
     * @param extra An extra string to bake into the class id
     * @return the class id
     */
    public static long computeClassId(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final SemanticVersion version,
            @NonNull final String extra) {
        // NOTE: Once this is live on any network, the formula used to generate this key can NEVER
        // BE CHANGED or you won't ever be able to deserialize an exising state! If we get away from
        // this formula, we will need to hardcode known classId that had been previously generated.
        final var ver = "v" + version.major() + "." + version.minor() + "." + version.patch();
        return hashString(serviceName + ":" + stateKey + ":" + ver + ":" + extra);
    }

    // Will be moved to `NonCryptographicHashing` with
    // https://github.com/swirlds/swirlds-platform/issues/6421
    static long hashString(@NonNull final String s) {
        // Normalize the string so things are deterministic (different JVMs might be using different
        // default internal representation for strings, and we need to normalize that)
        final var data = getNormalisedStringBytes(s);
        long l = hashBytes(data);
        logger.debug(LogMarker.STARTUP.getMarker(), "Hashed string {} to {}", s, l);
        return l;
    }

    // Will be moved to `NonCryptographicHashing` with
    // https://github.com/swirlds/swirlds-platform/issues/6421
    private static long hashBytes(@NonNull final byte[] bytes) {
        // Go through the bytes. Conceptually, we get 8 bytes at a time, joined as a long,
        // and then xor it with our running hash. Then pass that into the hash64 function.
        // First, we divide the byte array into 8-byte blocks, and process them.
        final int numBlocks = bytes.length / 8;
        long hash = 0;
        for (int i = 0; i < (numBlocks * 8); i += 8) {
            hash ^= ((long) bytes[i] << 56);
            hash ^= ((long) bytes[i + 1] << 48);
            hash ^= ((long) bytes[i + 2] << 40);
            hash ^= ((long) bytes[i + 3] << 32);
            hash ^= (bytes[i + 4] << 24);
            hash ^= (bytes[i + 5] << 16);
            hash ^= (bytes[i + 6] << 8);
            hash ^= (bytes[i + 7]);
            hash = NonCryptographicHashing.hash64(hash);
        }

        // There may be 0 <= N <= 7 remaining bytes. Process these bytes
        final int numRemainingBytes = bytes.length - (numBlocks * 8);
        if (numRemainingBytes > 0) {
            for (int shift = numRemainingBytes * 8, i = (numBlocks * 8); i < bytes.length; i++, shift -= 8) {
                hash ^= ((long) bytes[i] << shift);
            }
            hash = NonCryptographicHashing.hash64(hash);
        }

        // We're done!
        return hash;
    }

    /**
     * Simple utility for checking whether the character is an underscore or dash on the ASCII
     * table.
     *
     * @param ch The character to check
     * @return True if the character is the underscore or dash character on the ascii table
     */
    private static boolean isAsciiUnderscoreOrDash(char ch) {
        return ch == '-' || ch == '_';
    }

    /**
     * Simple utility for checking whether the given character is A-Z or a-z on the ascii table.
     *
     * @param ch The character to check
     * @return True if the character is A-Z or a-z
     */
    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    /**
     * Simple utility to check whether the given character is a numeric digit between 0-9
     *
     * @param ch The character to check
     * @return True if the character is between ascii 0-9.
     */
    private static boolean isAsciiNumber(char ch) {
        return ch >= '0' && ch <= '9';
    }

    /**
     * Registers with the {@link ConstructableRegistry} system a class ID and a class. While this
     * will only be used for in-memory states, it is safe to register for on-disk ones as well.
     *
     * <p>The implementation will take the service name and the state key and compute a hash for it.
     * It will then convert the hash to a long, and use that as the class ID. It will then register
     * an {@link InMemoryWritableKVState}'s value merkle type to be deserialized, answering with the
     * generated class ID.
     *
     * @param md The state metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerWithSystem(
            @NonNull final StateMetadata md, @NonNull ConstructableRegistry constructableRegistry) {
        // Register with the system the uniqueId as the "classId" of an InMemoryValue. There can be
        // multiple id's associated with InMemoryValue. The secret is that the supplier captures the
        // various delegate writers and parsers, and so can parse/write different types of data
        // based on the id.
        try {
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    InMemoryValue.class,
                    () -> new InMemoryValue(
                            md.inMemoryValueClassId(),
                            md.stateDefinition().keyCodec(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKey.class,
                    () -> new OnDiskKey<>(
                            md.onDiskKeyClassId(), md.stateDefinition().keyCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKeySerializer.class,
                    () -> new OnDiskKeySerializer<>(
                            md.onDiskKeySerializerClassId(),
                            md.onDiskKeyClassId(),
                            md.stateDefinition().keyCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValue.class,
                    () -> new OnDiskValue<>(
                            md.onDiskValueClassId(), md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValueSerializer.class,
                    () -> new OnDiskValueSerializer<>(
                            md.onDiskValueSerializerClassId(),
                            md.onDiskValueClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    SingletonNode.class,
                    () -> new SingletonNode<>(
                            md.serviceName(),
                            md.stateDefinition().stateKey(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec(),
                            null)));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    QueueNode.class,
                    () -> new QueueNode<>(
                            md.serviceName(),
                            md.stateDefinition().stateKey(),
                            md.queueNodeClassId(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(StringLeaf.class, StringLeaf::new));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    ValueLeaf.class,
                    () -> new ValueLeaf<>(
                            md.singletonClassId(), md.stateDefinition().valueCodec())));
        } catch (ConstructableRegistryException e) {
            // This is a fatal error.
            throw new IllegalStateException(
                    "Failed to register with the system '"
                            + md.serviceName()
                            + ":"
                            + md.stateDefinition().stateKey()
                            + "'",
                    e);
        }
    }
}
