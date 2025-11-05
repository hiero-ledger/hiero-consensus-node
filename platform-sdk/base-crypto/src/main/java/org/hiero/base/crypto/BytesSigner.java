// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Functional interface for signing a bytes. Intended to be a replacement for {@link Signer} because of the following
 * reasons:
 * <ul>
 *     <li>The {@link Signature} instance returned is self-serializable, which is functionality that will be removed</li>
 *     <li>The returned instance holds a {@link SignatureType} which bundles schema and implementation information</li>
 *     <li>This interface uses immutable bytes, which is safer</li>
 * </ul>
 */
public interface BytesSigner {
    /**
     * Sign the supplied data
     *
     * @param data the data to sign
     * @return signature bytes
     */
    @NonNull
    Bytes sign(@NonNull Bytes data);
}
