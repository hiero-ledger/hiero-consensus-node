// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io;

import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.io.SerializableDet;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * <p>
 * An object that can serialize itself to a stream and a directory on a file system.
 */
public interface ExternalSelfSerializable extends SerializableDet {

    /**
     * Serialize data both to the stream and to a directory. The data serialized to the stream
     * must be sufficient for {@link #deserialize(SerializableDataInputStream, Path, int)}
     * to fully reconstruct the object. Any data written to disk MUST be written to a file that
     * is contained by the provided directory.
     *
     * @param out
     * 		the stream to write to
     * @param outputDirectory
     * 		a location on disk where data can be written. When saving state, this is
     * 		the same directory that holds the signed state file.
     * @throws IOException
     * 		thrown in case of an IO exception
     */
    void serialize(SerializableDataOutputStream out, Path outputDirectory) throws IOException;

    /**
     * Reconstruct this object using the data serialized by
     * {@link #serialize(SerializableDataOutputStream, Path)}.
     * This method may load additional data from the provided directory.
     *
     * @param in
     * 		The input stream.
     * @param inputDirectory
     * 		a location on disk where data can be read. Corresponds to the directory passed to
     *        {@link #serialize(SerializableDataOutputStream, Path)}. When reading a saved state,
     * 		this is the same directory that holds the signed state file.
     * @param version
     * 		The version at which this object was serialized. Guaranteed to be greater or equal to the
     * 		minimum version and less than or equal to the current version.
     * @throws IOException
     * 		thrown in case of an IO exception
     */
    void deserialize(SerializableDataInputStream in, Path inputDirectory, int version) throws IOException;
}
