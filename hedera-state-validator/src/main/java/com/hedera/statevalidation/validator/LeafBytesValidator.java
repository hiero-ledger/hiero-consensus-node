// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validator interface for processing P2KV (Path to Key/Value) data items.
 *
 * <p>Implementations receive {@link VirtualLeafBytes} containing serialized key-value pairs
 * from the MerkleDB leaf store. Called concurrently from multiple processor threads.
 *
 * @see Validator
 */
public interface LeafBytesValidator extends Validator {

    /**
     * Processes a single virtual leaf bytes entry.
     *
     * @param dataLocation the packed data location (file index + byte offset) of this entry
     * @param leafBytes the parsed leaf data containing path, key bytes, and value bytes
     */
    void processLeafBytes(long dataLocation, @NonNull VirtualLeafBytes<?> leafBytes);
}
