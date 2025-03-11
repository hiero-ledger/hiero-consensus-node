// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams.internal;

/**
 * Version numbers for the merkle serialization protocol.
 */
public final class MerkleSerializationProtocol {

    /**
     * The original merkle serialization protocol.
     */
    public static final int VERSION_1_ORIGINAL = 1;

    /**
     * Added an object that is serialized to the stream that contains various stream configuration options.
     */
    public static final int VERSION_2_ADDED_OPTIONS = 2;

    /**
     * Removed serialization options, as they were no longer needed after serialization simplifications.
     */
    public static final int VERSION_3_REMOVED_OPTIONS = 3;

    /**
     * The current protocol version.
     */
    public static final int CURRENT = VERSION_3_REMOVED_OPTIONS;

    private MerkleSerializationProtocol() {}
}
