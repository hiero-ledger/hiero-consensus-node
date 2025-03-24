// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import org.hiero.consensus.model.io.SelfSerializable;

public final class SerializableStreamConstants {

    private SerializableStreamConstants() {}

    /** The value of the length of an array/list will when the array/list is null */
    public static final int NULL_LIST_ARRAY_LENGTH = -1;
    /** The class ID of a {@link SelfSerializable} instance when the instance is null */
    public static final long NULL_CLASS_ID = Long.MIN_VALUE;
    /** The version of a {@link SelfSerializable} instance when the instance is null */
    public static final int NULL_VERSION = Integer.MIN_VALUE;
    /** The value of Instant.epochSecond when instant is null */
    public static final long NULL_INSTANT_EPOCH_SECOND = Long.MIN_VALUE;

    /** number of bytes used by a boolean variable during serialization */
    public static final int BOOLEAN_BYTES = Byte.BYTES;
    /** number of bytes used by a class ID variable during serialization */
    public static final int CLASS_ID_BYTES = Long.BYTES;
    /** number of bytes used by a version variable during serialization */
    public static final int VERSION_BYTES = Integer.BYTES;

    /**
     * The current version of the serialization protocol implemented by {@link SerializableDataOutputStreamImpl} and
     * {@link SerializableDataInputStreamImpl}
     */
    public static final int SERIALIZATION_PROTOCOL_VERSION = 1;

    /**
     * Should stream methods that use checksums use checksums by default?
     */
    public static final boolean DEFAULT_CHECKSUM = false;
}
