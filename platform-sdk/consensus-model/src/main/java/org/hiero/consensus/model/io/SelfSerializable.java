// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A SerializableDet that knows how to serialize and deserialize itself.
 */
public interface SelfSerializable extends SerializableDet, FunctionalSerialize {

    /**
     * Deserializes an instance that has been previously serialized by {@link FunctionalSerialize#serialize(SerializableDataOutputStream)}.
     * This method should support all versions of the serialized data.
     *
     * @param in
     * 		The stream to read from.
     * @param version
     * 		The version of the serialized instance. Guaranteed to be greater or equal to the minimum version
     * 		and less than or equal to the current version.
     * @throws IOException
     * 		Thrown in case of an IO exception.
     */
    void deserialize(@NonNull SerializableDataInputStream in, int version) throws IOException;
}
