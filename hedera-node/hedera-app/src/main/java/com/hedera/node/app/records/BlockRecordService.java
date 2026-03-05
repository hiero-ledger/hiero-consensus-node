// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.records.schemas.V0560BlockRecordSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

/**
 * A {@link Service} for managing the state of the running hashes and block information. Used by the
 * {@link BlockRecordManagerImpl}. This service is not exposed outside `hedera-app`.
 */
@Singleton
public final class BlockRecordService implements Service {
    /** The original hash, only used at genesis */
    private static final Bytes GENESIS_HASH = Bytes.wrap(new byte[48]);

    /** The name of this service */
    public static final String NAME = "BlockRecordService";

    /**
     * The epoch timestamp, a placeholder for time of an event that has never happened.
     */
    public static final Timestamp EPOCH = new Timestamp(0, 0);
    /**
     * The block info at genesis.
     */
    public static final BlockInfo GENESIS_BLOCK_INFO = BlockInfo.newBuilder()
            .lastBlockNumber(-1)
            .firstConsTimeOfLastBlock(EPOCH)
            .blockHashes(Bytes.EMPTY)
            .consTimeOfLastHandledTxn(EPOCH)
            .migrationRecordsStreamed(true)
            .firstConsTimeOfCurrentBlock(EPOCH)
            .lastUsedConsTime(EPOCH)
            .lastIntervalProcessTime(EPOCH)
            .build();
    /**
     * The running hashes at genesis.
     */
    public static final RunningHashes GENESIS_RUNNING_HASHES =
            RunningHashes.newBuilder().runningHash(GENESIS_HASH).build();

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490BlockRecordSchema());
        registry.register(new V0560BlockRecordSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).put(GENESIS_BLOCK_INFO);
        writableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID).put(GENESIS_RUNNING_HASHES);
        return true;
    }
}
