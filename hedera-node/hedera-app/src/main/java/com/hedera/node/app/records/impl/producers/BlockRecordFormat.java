// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Defines API for computing running hashes, and converting {@link SingleTransactionRecord}s into
 * {@link SerializedSingleTransactionRecord}s. These operations are dependent on the block record version (v6, v7, etc),
 * but are not otherwise related to the construction of the record file or writing the bytes to a destination such as
 * disk or a socket. Thus, an implementation of this interface for a particular version of the block record format can
 * be reused across the different implementations of {@link BlockRecordWriter}.
 */
public interface BlockRecordFormat {
    // FUTURE remove once <a href="https://github.com/hashgraph/pbj/issues/44">PBJ Issue 44</a> is fixed
    int WIRE_TYPE_DELIMITED = 2;
    // FUTURE remove once <a href="https://github.com/hashgraph/pbj/issues/44">PBJ Issue 44</a> is fixed
    int TAG_TYPE_BITS = 3;

    /**
     * Serialize a record stream item into an intermediary format that can be used for computing running hashes or
     * writing to the file.
     *
     * @param singleTransactionRecord The records of a single transaction to serialize
     * @param blockNumber The current block number this transaction will be written into
     * @param hapiVersion The hapi protobuf version
     * @return the serialized intermediary format item
     */
    SerializedSingleTransactionRecord serialize(
            @NonNull final SingleTransactionRecord singleTransactionRecord,
            final long blockNumber,
            @NonNull final SemanticVersion hapiVersion);

    /**
     * Given the starting running hash and a stream of serialized record stream items, compute the new running hash by
     * adding the items to the hash one at a time.
     *
     * @param startRunningHash the starting current running hash of all record stream items up to this point in time.
     * @param serializedItems the list of intermediary format serialized record stream items to add to the running hash
     * @return the new running hash, or startRunningHash if there were no items to add
     */
    Bytes computeNewRunningHash(
            @NonNull final Bytes startRunningHash,
            @NonNull final List<SerializedSingleTransactionRecord> serializedItems);
}
