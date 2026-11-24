// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.node.app.records.BlockRecordService.GENESIS_BLOCK_INFO;
import static com.hedera.node.app.records.BlockRecordService.GENESIS_RUNNING_HASHES;
import static com.hedera.node.app.records.BlockRecordService.genesisBlockInfo;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockRecordServiceTest {
    @Test
    void testGetServiceName() {
        BlockRecordService blockRecordService = new BlockRecordService();
        assertEquals(BlockRecordService.NAME, blockRecordService.getServiceName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void genesisPreviewStreamIsOverwrittenOnlyWhenBornWithCutover(final boolean cutoverEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.enableCutover", cutoverEnabled)
                .getOrCreateConfig();
        final var blockInfo = new AtomicReference<BlockInfo>();
        final var runningHashes = new AtomicReference<RunningHashes>();
        final var writableStates = MapWritableStates.builder()
                .state(new FunctionWritableSingletonState<>(
                        BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfo::get, blockInfo::set))
                .state(new FunctionWritableSingletonState<>(
                        RUNNING_HASHES_STATE_ID, RUNNING_HASHES_STATE_LABEL, runningHashes::get, runningHashes::set))
                .build();

        new BlockRecordService().doGenesisSetup(writableStates, config);

        // A network born with the cutover enabled has no preview stream, so a restart must not overwrite it
        final var expected = GENESIS_BLOCK_INFO
                .copyBuilder()
                .previewStreamOverwritten(cutoverEnabled)
                .build();
        assertEquals(
                expected,
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get());
        assertEquals(expected, genesisBlockInfo(config));
        assertEquals(
                GENESIS_RUNNING_HASHES,
                writableStates
                        .<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID)
                        .get());
    }
}
