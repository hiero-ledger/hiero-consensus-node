// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams.internal;

import java.io.IOException;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * @deprecated this class fragment is present for migration purposes only
 */
@Deprecated(forRemoval = true)
public class MerkleTreeSerializationOptions implements SelfSerializable {
    private static final long CLASS_ID = 0x76a4b529cfba0bccL;
    private static final int CLASS_VERSION = 1;

    public static final int MAX_LENGTH_BYTES = 128;

    public MerkleTreeSerializationOptions() {}

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        // Read and discard the data for this class
        in.readByteArray(MAX_LENGTH_BYTES);
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        throw new UnsupportedOperationException("this class is deprecated and should not be used");
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }
}
