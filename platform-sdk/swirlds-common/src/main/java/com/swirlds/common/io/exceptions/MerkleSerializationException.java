// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.exceptions;

import static org.hiero.base.constructable.ClassIdFormatter.versionedClassIdString;

import com.swirlds.common.merkle.MerkleNode;
import java.io.IOException;

/**
 * This exception can be thrown during serialization or deserialization of a merkle tree.
 */
public class MerkleSerializationException extends IOException {

    public MerkleSerializationException() {}

    public MerkleSerializationException(final String message) {
        super(message);
    }

    /**
     * Throw an exception for a particular node.
     *
     * @param message
     * 		the message to be included in the exception
     * @param node
     * 		the node that caused the exception
     */
    public MerkleSerializationException(final String message, final MerkleNode node) {
        super(message + " [" + versionedClassIdString(node) + "]");
    }

    public MerkleSerializationException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public MerkleSerializationException(final Throwable cause) {
        super(cause);
    }
}
