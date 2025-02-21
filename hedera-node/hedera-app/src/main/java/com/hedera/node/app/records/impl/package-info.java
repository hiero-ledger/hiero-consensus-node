// SPDX-License-Identifier: Apache-2.0
/**
 * This package contains a complete implementation of {@link com.hedera.node.app.records.BlockRecordManager}. This
 * implementation delegates to {@link com.hedera.node.app.records.impl.BlockRecordStreamProducer}s to compute the
 * rolling hashes of {@link com.hedera.hapi.streams.RecordStreamItem}s, and to write the records to a file, socket,
 * or other destination. Implementations of {@link com.hedera.node.app.records.impl.BlockRecordStreamProducer} are
 * provided in the {@link com.hedera.node.app.records.impl.producers} package.
 */
package com.hedera.node.app.records.impl;
