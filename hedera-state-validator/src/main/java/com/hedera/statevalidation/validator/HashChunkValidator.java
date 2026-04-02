// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validator interface for processing ID2C (Chunk ID to Chunk) data items.
 *
 * <p>Implementations receive {@link VirtualHashChunk} entries containing hash chunks
 * from the MerkleDB hash store. Called concurrently from multiple processor threads.
 *
 * @see Validator
 */
public interface HashChunkValidator extends Validator {

    /**
     * Processes a single virtual hash chunk entry.
     *
     * @param virtualHashChunk the parsed hash chunk containing path and hash data
     */
    void processHashChunk(@NonNull VirtualHashChunk virtualHashChunk);
}
