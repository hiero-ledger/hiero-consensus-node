// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io;

import com.hedera.pbj.runtime.io.PbjWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

@FunctionalInterface
public interface FunctionalSerializePbj {
    /**
     * Serializes the data in the object in a deterministic manner. The class ID and version number should not be
     * written by this method, it should only include internal data.
     *
     * @param out
     * 		The stream to write to.
     * @throws IOException
     * 		Thrown in case of an IO exception.
     */
    void serialize(@NonNull PbjWriter out) throws IOException;
}
