// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import static org.hiero.base.utility.CommonUtils.getNormalisedStringBytes;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.NonCryptographicHashing;

/**
 * Holds metadata related to a registered service's state.
 *
 * @param <K> The type of the state key
 * @param <V> The type of the state value
 */
public final class StateMetadata<K, V> {
    // The application framework reuses the same merkle nodes for different types of encoded data.
    // When written to saved state, the type of data is determined with a "class ID", which is just
    // a long. When a saved state is deserialized, the platform will read the "class ID" and then
    // lookup in ConstructableRegistry the associated class to use for parsing the data.
    //
    // We generate class IDs dynamically based on the StateMetadata. The algorithm used for generating
    // this class ID cannot change in the future, otherwise state already in the saved state file
    // will not be retrievable!
    private static final String ON_DISK_KEY_CLASS_ID_SUFFIX = "OnDiskKey";
    private static final String ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX = "OnDiskKeySerializer";
    private static final String ON_DISK_VALUE_CLASS_ID_SUFFIX = "OnDiskValue";
    private static final String ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX = "OnDiskValueSerializer";
    private static final String IN_MEMORY_VALUE_CLASS_ID_SUFFIX = "InMemoryValue";
    private static final String SINGLETON_CLASS_ID_SUFFIX = "SingletonLeaf";
    private static final String QUEUE_NODE_CLASS_ID_SUFFIX = "QueueNode";
    private static final Logger logger = LogManager.getLogger(StateMetadata.class);

    private final String serviceName;
    private final Schema schema;
    private final StateDefinition<K, V> stateDefinition;
    private final long onDiskKeyClassId;
    private final long onDiskKeySerializerClassId;
    private final long onDiskValueClassId;
    private final long onDiskValueSerializerClassId;
    private final long inMemoryValueClassId;
    private final long singletonClassId;
    private final long queueNodeClassId;

    /**
     * Create an instance.
     *
     * @param serviceName The name of the service
     * @param schema The {@link Schema} that defined the state
     * @param stateDefinition The {@link StateDefinition}
     */
    public StateMetadata(
            @NonNull String serviceName, @NonNull Schema schema, @NonNull StateDefinition<K, V> stateDefinition) {
        this.serviceName = validateServiceName(serviceName);
        this.schema = schema;
        this.stateDefinition = stateDefinition;

        final var stateKey = stateDefinition.stateKey();
        final var version = schema.getVersion();
        this.onDiskKeyClassId = computeClassId(serviceName, stateKey, version, ON_DISK_KEY_CLASS_ID_SUFFIX);
        this.onDiskKeySerializerClassId =
                computeClassId(serviceName, stateKey, version, ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX);
        this.onDiskValueClassId = computeClassId(serviceName, stateKey, version, ON_DISK_VALUE_CLASS_ID_SUFFIX);
        this.onDiskValueSerializerClassId =
                computeClassId(serviceName, stateKey, version, ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX);
        this.inMemoryValueClassId = computeClassId(serviceName, stateKey, version, IN_MEMORY_VALUE_CLASS_ID_SUFFIX);
        this.singletonClassId = computeClassId(serviceName, stateKey, version, SINGLETON_CLASS_ID_SUFFIX);
        this.queueNodeClassId = computeClassId(serviceName, stateKey, version, QUEUE_NODE_CLASS_ID_SUFFIX);
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
    public static long hashString(@NonNull final String s) {
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
    public static String validateIdentifier(@NonNull final String stateKey) {
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

    public String serviceName() {
        return serviceName;
    }

    public Schema schema() {
        return schema;
    }

    public @NonNull StateDefinition<K, V> stateDefinition() {
        return stateDefinition;
    }

    public long onDiskKeyClassId() {
        return onDiskKeyClassId;
    }

    public long onDiskKeySerializerClassId() {
        return onDiskKeySerializerClassId;
    }

    public long onDiskValueClassId() {
        return onDiskValueClassId;
    }

    public long onDiskValueSerializerClassId() {
        return onDiskValueSerializerClassId;
    }

    public long inMemoryValueClassId() {
        return inMemoryValueClassId;
    }

    public long singletonClassId() {
        return singletonClassId;
    }

    public long queueNodeClassId() {
        return queueNodeClassId;
    }
}
