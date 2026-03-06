// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validator interface for processing P2H (Path to Hash) data items.
 *
 * <p>Implementations receive {@link VirtualHashRecord} entries containing internal node hashes
 * from the MerkleDB hash store. Called concurrently from multiple processor threads.
 *
 * @see Validator
 */
public interface HashRecordValidator extends Validator {

    /**
     * Processes a single virtual hash record entry.
     *
     * @param virtualHashRecord the parsed hash record containing path and hash data
     */
    void processHashRecord(@NonNull VirtualHashRecord virtualHashRecord);
}
