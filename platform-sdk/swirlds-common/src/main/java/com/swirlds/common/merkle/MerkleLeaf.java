// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle;

import com.swirlds.common.io.ExternalSelfSerializable;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.crypto.SerializableHashable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A Merkle Leaf has only data and does not have children.
 */
public interface MerkleLeaf extends MerkleNode, SerializableHashable, ExternalSelfSerializable {

    /**
     * {@inheritDoc}
     */
    @Override
    MerkleLeaf copy();

    /**
     * {@inheritDoc}
     */
    @Override
    default void serialize(final SerializableDataOutputStream out, final Path outputDirectory) throws IOException {
        // Default implementation ignores the provided directory. Override this method to utilize the directory.
        serialize(out);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default void deserialize(final SerializableDataInputStream in, final Path inputDirectory, final int version)
            throws IOException {
        // Default implementation ignores the provided directory. Override this method to utilize the directory.
        deserialize(in, version);
    }
}
