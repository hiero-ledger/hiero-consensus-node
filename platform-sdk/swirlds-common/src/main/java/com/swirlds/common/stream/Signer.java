// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import com.swirlds.common.crypto.Signature;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An object capable of signing data.
 */
@FunctionalInterface
public interface Signer {

    /**
     * generate signature bytes for given data
     *
     * @param data an array of bytes
     * @return signature bytes
     */
    @NonNull
    Signature sign(@NonNull byte[] data);
}
