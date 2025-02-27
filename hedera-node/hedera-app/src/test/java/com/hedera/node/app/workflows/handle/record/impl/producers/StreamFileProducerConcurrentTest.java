// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record.impl.producers;

import static com.hedera.node.app.records.RecordTestData.VERSION;

import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.StreamFileProducerConcurrent;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ForkJoinPool;

final class StreamFileProducerConcurrentTest extends StreamFileProducerTest {
    @Override
    BlockRecordStreamProducer createStreamProducer(@NonNull final BlockRecordWriterFactory factory) {
        return new StreamFileProducerConcurrent(
                BlockRecordFormatV6.INSTANCE, factory, ForkJoinPool.commonPool(), VERSION);
    }
}
