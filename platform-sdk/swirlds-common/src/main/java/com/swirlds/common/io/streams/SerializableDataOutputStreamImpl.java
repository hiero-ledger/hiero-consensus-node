// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.io.streams.SerializableStreamConstants.BOOLEAN_BYTES;
import static com.swirlds.common.io.streams.SerializableStreamConstants.CLASS_ID_BYTES;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_CLASS_ID;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_VERSION;
import static com.swirlds.common.io.streams.SerializableStreamConstants.SERIALIZATION_PROTOCOL_VERSION;
import static com.swirlds.common.io.streams.SerializableStreamConstants.VERSION_BYTES;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.hiero.consensus.model.io.FunctionalSerialize;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.SerializableDet;
import org.hiero.consensus.model.io.SerializableWithKnownLength;

/**
 * A drop-in replacement for {@link DataOutputStream}, which handles SerializableDet classes specially.
 * It is designed for use with the SerializableDet interface, and its use is described there.
 */
public class SerializableDataOutputStreamImpl extends AugmentedDataOutputStream {
    /** A stream used to write PBJ objects */
    private final WritableSequentialData writableSequentialData;

    /**
     * Creates a new data output stream to write data to the specified
     * underlying output stream. The counter <code>written</code> is
     * set to zero.
     *
     * @param out
     * 		the underlying output stream, to be saved for later
     * 		use.
     * @see java.io.FilterOutputStream#out
     */
    public SerializableDataOutputStreamImpl(OutputStream out) {
        super(out);
        writableSequentialData = new WritableStreamingData(out);
    }

    /**
     * Write the serialization protocol version number to the stream. Should be used when serializing to a file that
     * can be read by future versions.
     *
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public void writeProtocolVersion() throws IOException {
        this.writeInt(SERIALIZATION_PROTOCOL_VERSION);
    }

    private void writeSerializable(
            SelfSerializable serializable, boolean writeClassId, FunctionalSerialize serializeMethod)
            throws IOException {
        if (serializable == null) {
            if (writeClassId) {
                writeLong(NULL_CLASS_ID);
            } else {
                writeInt(NULL_VERSION);
            }
            return;
        }
        writeClassIdVersion(serializable, writeClassId);
        serializeMethod.serialize(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeSerializable(SelfSerializable serializable, boolean writeClassId) throws IOException {
        writeSerializable(serializable, writeClassId, serializable);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void writeSerializableIterableWithSize(
            Iterator<T> iterator, int size, boolean writeClassId, boolean allSameClass) throws IOException {
        this.writeInt(size);
        if (size == 0) {
            return;
        }
        writeBoolean(allSameClass);
        // if the class ID and version is written only once, we need to write it when we come across
        // the first non-null member, this variable will keep track of whether its written or not
        boolean classIdVersionWritten = false;
        while (iterator.hasNext()) {
            SelfSerializable serializable = iterator.next();
            if (!allSameClass) {
                // if classes are different, we just write every class one by one
                writeSerializable(serializable, writeClassId);
                continue;
            }
            if (serializable == null) {
                writeBoolean(true);
                continue;
            }
            writeBoolean(false);
            if (!classIdVersionWritten) {
                // this is the first non-null member, so we write the ID and version
                writeClassIdVersion(serializable, writeClassId);
                classIdVersionWritten = true;
            }
            serializable.serialize(this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void writeSerializableList(
            List<T> list, boolean writeClassId, boolean allSameClass) throws IOException {

        if (list == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
            return;
        }
        writeSerializableIterableWithSize(list.iterator(), list.size(), writeClassId, allSameClass);
    }

    /**
     * Writes an array of {@link SelfSerializable} objects to the stream
     *
     * @param array
     * 		the array to write, can be null
     * @param writeClassId
     * 		set to true if the classID should be written. This can be false if the class is known when
     * 		de-serializing
     * @param allSameClass
     * 		should be set to true if all the objects in the list are the same class
     * @param <T>
     * 		the class stored in the list
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public <T extends SelfSerializable> void writeSerializableArray(
            T[] array, boolean writeClassId, boolean allSameClass) throws IOException {

        if (array == null) {
            writeSerializableList(null, writeClassId, allSameClass);
        } else {
            writeSerializableList(Arrays.asList(array), writeClassId, allSameClass);
        }
    }

    /**
     * Get the serialized byte length an array of {@link SerializableWithKnownLength} objects
     *
     * @param array
     * 		the array to write, can be null
     * @param writeClassId
     * 		set to true if the classID should be written. This can be false if the class is known when
     * 		de-serializing
     * @param allSameClass
     * 		should be set to true if all the objects in the array are the same class
     * @param <T>
     * 		the class stored in the array
     */
    public static <T extends SerializableWithKnownLength> int getSerializedLength(
            final T[] array, final boolean writeClassId, final boolean allSameClass) {
        int totalByteLength = Integer.BYTES; // length of array size
        if (array == null || array.length == 0) {
            return totalByteLength;
        }

        totalByteLength += BOOLEAN_BYTES;
        boolean classIdVersionWritten = false;
        for (final T t : array) {
            if (!allSameClass) {
                totalByteLength += getInstanceSerializedLength(t, true, writeClassId);
                continue;
            }
            if (t == null) {
                totalByteLength += BOOLEAN_BYTES;
                continue;
            }
            totalByteLength += BOOLEAN_BYTES;
            if (!classIdVersionWritten) {
                // this is the first non-null member, so we write the ID and version
                totalByteLength += VERSION_BYTES;
                if (writeClassId) {
                    totalByteLength += CLASS_ID_BYTES;
                }
                classIdVersionWritten = true;
            }
            // version and class info already written
            totalByteLength += getInstanceSerializedLength(t, false, false);
        }

        return totalByteLength;
    }

    /**
     * Get the serialized byte length of {@link SerializableWithKnownLength} object
     *
     * @param data
     * 		array to write, should not be null
     * @param writeVersion
     *      set to true if the version will be serialized
     * @param writeClassId
     * 		set to true if the classID should be written. This can be false if the class is known when
     * 		de-serializing
     */
    public static <T extends SerializableWithKnownLength> int getInstanceSerializedLength(
            final T data, final boolean writeVersion, final boolean writeClassId) {
        if (data == null) {
            return writeClassId ? CLASS_ID_BYTES : writeVersion ? VERSION_BYTES : 0;
        }
        int totalByteLength = 0;
        if (writeClassId) {
            totalByteLength += CLASS_ID_BYTES;
        }
        if (writeVersion) {
            totalByteLength += VERSION_BYTES; // version integer
        }
        totalByteLength += data.getSerializedLength(); // data its own content serialized length
        return totalByteLength;
    }

    /** This method assumes serializable is not null */
    protected void writeClassIdVersion(final SerializableDet serializable, final boolean writeClassId)
            throws IOException {

        if (writeClassId) {
            this.writeLong(serializable.getClassId());
        }
        this.writeInt(serializable.getVersion());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> void writePbjRecord(@NonNull final T record, @NonNull final Codec<T> codec) throws IOException {
        writeInt(codec.measureRecord(record));
        codec.write(record, writableSequentialData);
    }
}
