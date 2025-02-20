// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record.impl.producers;

import static com.hedera.node.app.records.RecordTestData.VERSION;

import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.StreamFileProducerSingleThreaded;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import edu.umd.cs.findbugs.annotations.NonNull;

final class StreamFileProducerSingleThreadedTest extends StreamFileProducerTest {

    @Override
    BlockRecordStreamProducer createStreamProducer(@NonNull final BlockRecordWriterFactory factory) {
        return new StreamFileProducerSingleThreaded(BlockRecordFormatV6.INSTANCE, factory, VERSION);
    }
}
