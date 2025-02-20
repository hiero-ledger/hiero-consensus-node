// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io;

import com.swirlds.common.io.streams.SerializableDataOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

@FunctionalInterface
public interface FunctionalSerialize {
    /**
     * Serializes the data in the object in a deterministic manner. The class ID and version number should not be
     * written by this method, it should only include internal data.
     *
     * @param out
     * 		The stream to write to.
     * @throws IOException
     * 		Thrown in case of an IO exception.
     */
    void serialize(@NonNull SerializableDataOutputStream out) throws IOException;
}
