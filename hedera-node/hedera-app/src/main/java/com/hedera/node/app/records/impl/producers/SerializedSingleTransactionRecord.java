// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers;

import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;

/**
 * Intermediate representation of a single transaction record
 *
 * @param hashSerializedRecordStreamItem Optional serialized record stream item for hashing
 * @param protobufSerializedRecordStreamItem Protobuf serialized record stream item
 * @param sideCarItemsBytes Protobuf serialized sidecar items
 * @param sideCarItems Original sidecar items
 */
public record SerializedSingleTransactionRecord(
        Bytes hashSerializedRecordStreamItem,
        Bytes protobufSerializedRecordStreamItem,
        List<Bytes> sideCarItemsBytes,
        List<TransactionSidecarRecord> sideCarItems) {}
