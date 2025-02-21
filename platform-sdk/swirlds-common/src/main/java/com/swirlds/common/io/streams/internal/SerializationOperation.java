/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.common.io.streams.internal;

import com.swirlds.common.io.streams.AugmentedDataInputStream;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.config.api.Configuration;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Describes a serialization operation.
 */
public enum SerializationOperation {

    /**
     * Marks the opening of the stream.
     */
    STREAM_OPENED,

    /**
     * {@link InputStream#read()}
     */
    READ,

    /**
     * {@link InputStream#skip(long)}
     */
    SKIP,

    /**
     * {@link InputStream#readAllBytes()}
     */
    READ_ALL_BYTES,

    /**
     * {@link InputStream#readNBytes(int)}
     */
    READ_N_BYTES,

    /**
     * {@link InputStream#readNBytes(int)}
     */
    READ_N_BYTES_ARRAY,

    /**
     * {@link InputStream#skipNBytes(long)}
     */
    SKIP_N_BYTES,

    /**
     * {@link DataInputStream#readFully(byte[])}
     */
    READ_FULLY,

    /**
     * {@link DataInputStream#readFully(byte[], int, int)}
     */
    READ_FULLY_OFFSET,

    /**
     * {@link DataInputStream#skipBytes(int)}
     */
    SKIP_BYTES,

    /**
     * {@link DataInputStream#readBoolean()}
     */
    READ_BOOLEAN,

    /**
     * {@link DataInputStream#readByte()}
     */
    READ_BYTE,

    /**
     * {@link DataInputStream#readUnsignedByte()}
     */
    READ_UNSIGNED_BYTE,

    /**
     * {@link DataInputStream#readShort()}
     */
    READ_SHORT,

    /**
     * {@link DataInputStream#readUnsignedShort()}
     */
    READ_UNSIGNED_SHORT,

    /**
     * {@link DataInputStream#readChar()}
     */
    READ_CHAR,

    /**
     * {@link DataInputStream#readInt()}
     */
    READ_INT,

    /**
     * {@link DataInputStream#readLong()}
     */
    READ_LONG,

    /**
     * {@link DataInputStream#readFloat()}
     */
    READ_FLOAT,

    /**
     * {@link DataInputStream#readDouble()}
     */
    READ_DOUBLE,

    /**
     * {@link DataInputStream#readLine()}
     */
    READ_LINE,

    /**
     * {@link DataInputStream#readUTF()}
     */
    READ_UTF,

    /**
     * {@link AugmentedDataInputStream#readByteArray(int)} and
     * {@link AugmentedDataInputStream#readByteArray(int, boolean)}
     */
    READ_BYTE_ARRAY,

    /**
     * {@link AugmentedDataInputStream#readIntList(int)} and
     * {@link AugmentedDataInputStream#readIntArray(int)}
     */
    READ_INT_LIST,

    /**
     * {@link AugmentedDataInputStream#readLongList(int)} and
     * {@link AugmentedDataInputStream#readLongArray(int)}
     */
    READ_LONG_LIST,

    /**
     * {@link AugmentedDataInputStream#readBooleanList(int)}
     */
    READ_BOOLEAN_LIST,

    /**
     * {@link AugmentedDataInputStream#readFloatList(int)} and
     * {@link AugmentedDataInputStream#readFloatArray(int)}
     */
    READ_FLOAT_LIST,

    /**
     * {@link AugmentedDataInputStream#readDoubleList(int)} and
     * {@link AugmentedDataInputStream#readDoubleArray(int)}
     */
    READ_DOUBLE_LIST,

    /**
     * {@link AugmentedDataInputStream#readStringList(int, int)} and
     * {@link AugmentedDataInputStream#readStringArray(int, int)}
     */
    READ_STRING_LIST,

    /**
     * {@link AugmentedDataInputStream#readInstant()}
     */
    READ_INSTANT,

    /**
     * {@link AugmentedDataInputStream#readNormalisedString(int)}
     */
    READ_NORMALISED_STRING,

    /**
     * All variants of {@link SerializableDataInputStream#readSerializable()}
     */
    READ_SERIALIZABLE,

    /**
     * All variants of {@link SerializableDataInputStream#readSerializableList(int, boolean, Supplier)}
     * and {@link SerializableDataInputStream#readSerializableArray(IntFunction, int, boolean, Set)}
     */
    READ_SERIALIZABLE_LIST,

    /**
     * {@link MerkleDataInputStream#readMerkleTree(Configuration, Path, int)}
     */
    READ_MERKLE_TREE,

    /**
     * Called every time {@link MerkleDataInputStream#readMerkleTree(Configuration, Path, int)} deserializes
     * a merkle node.
     */
    READ_MERKLE_NODE
}
