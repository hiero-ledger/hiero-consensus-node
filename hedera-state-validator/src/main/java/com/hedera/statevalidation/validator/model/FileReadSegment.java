// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

import com.hedera.statevalidation.validator.model.DiskDataItem.Type;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a segment of a data file assigned for parallel reading a specific byte range from it.
 *
 * @param reader the data file reader
 * @param type the MerkleDB data type
 * @param startByte the starting byte offset (inclusive)
 * @param endByte the ending byte offset (exclusive)
 */
public record FileReadSegment(
        @NonNull DataFileReader reader, @NonNull Type type, long startByte, long endByte) {}
