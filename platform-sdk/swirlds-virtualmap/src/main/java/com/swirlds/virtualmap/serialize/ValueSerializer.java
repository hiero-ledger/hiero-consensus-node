// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.serialize;

import com.swirlds.virtualmap.VirtualValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An interface to serialize values used in virtual maps. Virtual values are serializable in themselves,
 * but the corresponding interface, {@link SelfSerializable}, lacks some abilities needed by virtual
 * data sources. For example, there is no way to easily get serialized value size in bytes, and there
 * are no methods to serialize / deserialize values to / from byte buffers.
 *
 * <p>Serialization bytes used by value serializers may or may not be identical to bytes used when values
 * are self-serialized. In many cases value serializers will just delegate serialization to values, just
 * returning the size of serialized byte array. On deserialization, typical implementation is to
 * create a new value object and call its {@link SelfSerializable#deserialize(SerializableDataInputStream, int)}
 * method.
 *
 * @param <V>
 *     Virtual value type
 */
public interface ValueSerializer<V extends VirtualValue> extends BaseSerializer<V>, SelfSerializable {

    @Override
    default void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        // most value serializers are stateless, so there is nothing to serialize
    }

    @Override
    default void deserialize(@NonNull final SerializableDataInputStream in, int version) throws IOException {
        // most value serializers are stateless, so there is nothing to deserialize
    }
}
