// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.merkle.ExternalVirtualMapMetadata.CLASS_ID;

import com.swirlds.common.io.ExternalSelfSerializable;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * An empty shell to support migration from the previous versions upon deserialization
 */
@ConstructableClass(value = CLASS_ID)
@Deprecated(forRemoval = true)
public final class VirtualRootNode extends PartialBinaryMerkleInternal implements ExternalSelfSerializable {

    /**
     * Used for serialization.
     */
    public static final long CLASS_ID = 0x4a7d82719a5e1af5L;

    /**
     * This version number should be used to handle compatibility issues that may arise from any future changes
     */
    public static class ClassVersion {
        public static final int VERSION_3_NO_NODE_CACHE = 3;
        public static final int VERSION_4_BYTES = 4;
        public static final int CURRENT_VERSION = VERSION_4_BYTES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.CURRENT_VERSION;
    }

    @Override
    public void serialize(SerializableDataOutputStream out, Path outputDirectory) throws IOException {
        // no op
    }

    @Override
    public void deserialize(SerializableDataInputStream in, Path inputDirectory, int version) throws IOException {
        // no op
    }
}
