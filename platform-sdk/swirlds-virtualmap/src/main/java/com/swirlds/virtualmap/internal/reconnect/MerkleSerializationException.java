// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import java.io.IOException;

/**
 * This exception can be thrown during serialization or deserialization of a merkle tree.
 */
public class MerkleSerializationException extends IOException {

    public MerkleSerializationException() {}

    public MerkleSerializationException(final String message) {
        super(message);
    }
}
