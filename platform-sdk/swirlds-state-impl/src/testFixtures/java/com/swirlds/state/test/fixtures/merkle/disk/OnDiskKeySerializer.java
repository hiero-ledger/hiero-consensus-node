// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.disk;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.serialize.KeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.base.io.SelfSerializable;

/**
 * An implementation of {@link KeySerializer}, responsible for converting an {@link OnDiskKey} into
 * bytes when hashing, and when saving to disk, and when converting from bytes back into an object.
 * This class is <strong>NOT</strong> used by the state saving system, which relies on {@link
 * OnDiskKey} being self serializable.
 *
 * <p>However, this class does, itself, need to be {@link SelfSerializable} because it is part of
 * the structure of a {@link VirtualMap} and needs to be restored when loaded from saved-state.
 *
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 *
 * @param <K>
 */
@Deprecated
public final class OnDiskKeySerializer<K> implements KeySerializer<OnDiskKey<K>> {

    /**
     * This is a hint for virtual maps, it's the best guess as of now. Should be revisited later
     * based on the information about real mainnet entities.
     */
    private static final int TYPICAL_SIZE = 256;

    @Deprecated(forRemoval = true)
    private static final long CLASS_ID = 0x9992382838283412L;

    // Serializer version
    private static final int VERSION = 1;

    private final long serializerClassId;
    private final long keyClassId;
    private final Codec<K> codec;

    // Default constructor provided for ConstructableRegistry, TO BE REMOVED ASAP
    @Deprecated(forRemoval = true)
    public OnDiskKeySerializer() {
        serializerClassId = CLASS_ID; // BAD!!
        keyClassId = 0; // invalid class id
        codec = null;
    }

    public OnDiskKeySerializer(long serializerClassId, long keyClassId, @Nullable final Codec<K> codec) {
        this.serializerClassId = serializerClassId;
        this.keyClassId = keyClassId;
        this.codec = codec;
    }

    // Serializer info

    @Override
    public long getClassId() {
        return serializerClassId;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    // Key info

    @Override
    public long getCurrentDataVersion() {
        return OnDiskKey.VERSION;
    }

    // Key serialization

    @Override
    public int getSerializedSize() {
        // We're going to use variable size keys, always. MerkleDB was designed with
        // fast paths if you knew you were using a Long as the key -- but we really
        // cannot use that. The problem manifests itself with state proofs. We wanted
        // to be able to say that we only store a long for the account ID, and implicitly
        // know the shard and realm as they could be system properties when the JVM
        // is started up. The problem is that the shard and realm MUST be part of the
        // serialized bytes, because they MUST be part of the state proof. Otherwise, you
        // would only have a proof of the accountNum, but not the shard and realm (well,
        // you may be able to reconstruct the shard and realm via the signature on the
        // state proof, if each shard has its own public key / ledger id, but that is
        // very cumbersome!).
        //
        // With protobuf serialization, we can encode shard/realm/num as a single long
        // in many cases, since "0" is the default for the fields, and therefore can
        // be skipped entirely. And anyway they are varint encoded, so for low values
        // of shard and realm (likely to be true for a very long time), they will
        // at most consume 7 bits each of the value.
        //
        // While that may be true, the KeySerializer requires either fixed size or variable
        // size, and protobuf would either be fixed > 8 bytes, or variable sized, and being
        // fixed but greater than 8 bytes doesn't help us in performance, so we will
        // have to use variable size always.
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getSerializedSize(@NonNull final OnDiskKey<K> key) {
        assert codec != null;
        return codec.measureRecord(key.getKey());
    }

    @Override
    public int getTypicalSerializedSize() {
        return TYPICAL_SIZE;
    }

    // Key serialization

    @Override
    public void serialize(@NonNull final OnDiskKey<K> key, final @NonNull WritableSequentialData out) {
        assert codec != null;
        // Future work: https://github.com/hashgraph/pbj/issues/73
        try {
            codec.write(key.getKey(), out);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Key deserialization

    @Override
    public OnDiskKey<K> deserialize(@NonNull final ReadableSequentialData in) {
        assert codec != null;
        // Future work: https://github.com/hashgraph/pbj/issues/73
        try {
            final K k = codec.parse(in);
            Objects.requireNonNull(k);
            return new OnDiskKey<>(keyClassId, codec, k);
        } catch (final ParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(@NonNull final BufferedData bufferedData, @NonNull final OnDiskKey<K> keyToCompare) {
        assert codec != null;
        // Future work: https://github.com/hashgraph/pbj/issues/73
        try {
            return codec.fastEquals(keyToCompare.getKey(), bufferedData);
        } catch (final ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
