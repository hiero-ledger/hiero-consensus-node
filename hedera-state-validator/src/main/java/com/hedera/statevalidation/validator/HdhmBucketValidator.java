// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validator interface for processing K2P (Key to Path) data items.
 *
 * <p>Implementations receive {@link ParsedBucket} entries from the HalfDiskHashMap (HDHM)
 * key-to-path index. Called concurrently from multiple processor threads.
 *
 * @see Validator
 */
public interface HdhmBucketValidator extends Validator {

    /**
     * Processes a single HDHM bucket entry.
     *
     * @param bucketLocation the packed data location (file index + byte offset) of this bucket
     * @param bucket the parsed bucket containing key-to-path mappings
     */
    void processBucket(long bucketLocation, @NonNull ParsedBucket bucket);
}
