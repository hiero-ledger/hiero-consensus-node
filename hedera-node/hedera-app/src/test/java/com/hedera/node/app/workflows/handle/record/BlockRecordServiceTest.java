// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockRecordServiceTest {
    @Mock
    private PostUpgradeContext postUpgradeContext;

    private final BlockRecordService blockRecordService = new BlockRecordService();

    @Test
    void testGetServiceName() {
        assertEquals(BlockRecordService.NAME, blockRecordService.getServiceName());
    }

    @Test
    void postUpgradeSetupInitializesWrappedRecordVoting() {
        final var blockInfo = baseBlockInfo();
        final var writableStates = writableStatesWith(blockInfo);
        given(postUpgradeContext.configuration()).willReturn(configWith(true, 1L));

        assertTrue(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));

        final var updatedBlockInfo =
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get();
        assertFalse(updatedBlockInfo.votingComplete());
        assertEquals(blockInfo.lastBlockNumber() + 10, updatedBlockInfo.votingCompletionDeadlineBlockNumber());
        assertEquals(List.of(), updatedBlockInfo.migrationRootHashVotes());
    }

    @Test
    void postUpgradeSetupInitializesWrappedRecordVotingFromBareFalseDefault() {
        final var blockInfo =
                baseBlockInfo().copyBuilder().votingComplete(false).build();
        final var writableStates = writableStatesWith(blockInfo);
        given(postUpgradeContext.configuration()).willReturn(configWith(true, 1L));

        assertTrue(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));

        final var updatedBlockInfo =
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get();
        assertFalse(updatedBlockInfo.votingComplete());
        assertEquals(blockInfo.lastBlockNumber() + 10, updatedBlockInfo.votingCompletionDeadlineBlockNumber());
    }

    @Test
    void postUpgradeSetupSkipsWhenLiveWriteDisabled() {
        final var blockInfo = baseBlockInfo();
        final var writableStates = writableStatesWith(blockInfo);
        given(postUpgradeContext.configuration()).willReturn(configWith(false, 1L));

        assertFalse(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));
        assertEquals(
                blockInfo,
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get());
    }

    @Test
    void postUpgradeSetupSkipsWhenJumpstartDataMissing() {
        final var blockInfo = baseBlockInfo();
        final var writableStates = writableStatesWith(blockInfo);
        given(postUpgradeContext.configuration()).willReturn(configWith(true, 0L));

        assertFalse(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));
        assertEquals(
                blockInfo,
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get());
    }

    @Test
    void postUpgradeSetupSkipsWhenBlockInfoMissing() {
        final var writableStates = writableStatesWith(null);
        given(postUpgradeContext.configuration()).willReturn(configWith(true, 1L));

        assertFalse(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));
    }

    @Test
    void postUpgradeSetupDoesNotResetOpenVotingWindow() {
        final var blockInfo = baseBlockInfo()
                .copyBuilder()
                .votingComplete(false)
                .votingCompletionDeadlineBlockNumber(123)
                .build();
        final var writableStates = writableStatesWith(blockInfo);
        given(postUpgradeContext.configuration()).willReturn(configWith(true, 1L));

        assertFalse(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));
        assertEquals(
                blockInfo,
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get());
    }

    @Test
    void postUpgradeSetupDoesNotResetFinalizedVotingWindow() {
        final var blockInfo = baseBlockInfo()
                .copyBuilder()
                .votingCompletionDeadlineBlockNumber(123)
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .build();
        final var writableStates = writableStatesWith(blockInfo);
        given(postUpgradeContext.configuration()).willReturn(configWith(true, 1L));

        assertFalse(blockRecordService.doPostUpgradeSetup(writableStates, postUpgradeContext));
        assertEquals(
                blockInfo,
                writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get());
    }

    private static MapWritableStates writableStatesWith(final BlockInfo blockInfo) {
        final var blockInfoRef = new AtomicReference<>(blockInfo);
        return MapWritableStates.builder()
                .state(new FunctionWritableSingletonState<>(
                        BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfoRef::get, blockInfoRef::set))
                .build();
    }

    private static BlockInfo baseBlockInfo() {
        return BlockInfo.newBuilder()
                .lastBlockNumber(7)
                .firstConsTimeOfLastBlock(EPOCH)
                .blockHashes(Bytes.EMPTY)
                .migrationRecordsStreamed(true)
                .firstConsTimeOfCurrentBlock(EPOCH)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .votingComplete(true)
                .votingCompletionDeadlineBlockNumber(0)
                .build();
    }

    private static com.swirlds.config.api.Configuration configWith(
            final boolean liveWritePrevWrappedRecordHashes, final long jumpstartBlockNum) {
        return HederaTestConfigBuilder.create()
                .withValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", liveWritePrevWrappedRecordHashes)
                .withValue("blockStream.jumpstart.blockNum", jumpstartBlockNum)
                .getOrCreateConfig();
    }
}
