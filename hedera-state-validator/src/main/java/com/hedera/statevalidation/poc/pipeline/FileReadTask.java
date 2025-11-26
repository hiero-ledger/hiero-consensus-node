// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.pipeline;

import com.hedera.statevalidation.poc.model.DiskDataItem.Type;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a task for reading a specific byte range (chunk) from a data file.
 *
 * @param reader the data file reader
 * @param type the MerkleDB data type
 * @param startByte the starting byte offset (inclusive)
 * @param endByte the ending byte offset (exclusive)
 */
public record FileReadTask(@NonNull DataFileReader reader, @NonNull Type type, long startByte, long endByte) {}
