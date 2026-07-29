// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io;

import com.hedera.pbj.runtime.io.PbjReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

@FunctionalInterface
public interface FunctionalDeserializePbj {
    /**
     * Deserializes the object from the given buffer. The class ID and version number will have already
     * been consumed; only the internal data should be read.
     *
     * @param in the buffer to read from
     * @param version the version of the serialized data
     * @throws IOException if an IO error occurs
     */
    void deserialize(@NonNull PbjReader in, int version) throws IOException;
}
