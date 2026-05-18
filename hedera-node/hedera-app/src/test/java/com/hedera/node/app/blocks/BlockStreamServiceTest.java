// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_LABEL;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.impl.BlockStreamCutover;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.node.app.blocks.schemas.V0740BlockStreamSchema;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockStreamServiceTest {
    private static final Bytes WRAPPED_HASH = Bytes.wrap(new byte[HASH_SIZE]);
    private static final Bytes HASH_A = Bytes.fromHex("aa".repeat(HASH_SIZE));
    private static final Bytes HASH_B = Bytes.fromHex("bb".repeat(HASH_SIZE));
    private static final Bytes HASH_C = Bytes.fromHex("cc".repeat(HASH_SIZE));
    private static final Bytes HASH_D = Bytes.fromHex("dd".repeat(HASH_SIZE));

    @Mock
    private SchemaRegistry schemaRegistry;

    @Mock
    private PostUpgradeContext postUpgradeContext;

    private final BlockStreamService subject = new BlockStreamService();

    @Test
    void serviceNameAsExpected() {
        assertThat(subject.getServiceName()).isEqualTo("BlockStreamService");
    }

    @Test
    void enabledSubjectRegistersSchemas() {
        subject.registerSchemas(schemaRegistry);

        verify(schemaRegistry).register(argThat(s -> s instanceof V0560BlockStreamSchema));
        verify(schemaRegistry).register(argThat(s -> s instanceof V0740BlockStreamSchema));
    }

    @Test
    void doesPostUpgradeSetupCutoverWhenPending() {
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBlockStreamInfo = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(HASH_OF_ZERO)
                .build();
        final var blockStreamInfoRef = new AtomicReference<>(previewBlockStreamInfo);
        final var writableStates = writableBlockStreamStates(blockStreamInfoRef);
        givenCutoverContext(true, blockInfo, runningHashes);

        assertThat(subject.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isTrue();

        assertThat(writableStates
                        .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                        .get())
                .isEqualTo(BlockStreamCutover.blockStreamInfoFrom(blockInfo, runningHashes, previewBlockStreamInfo));
    }

    @Test
    void skipsPostUpgradeSetupWhenCutoverDisabled() {
        final var previewBlockStreamInfo = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(HASH_OF_ZERO)
                .build();
        final var writableStates = writableBlockStreamStates(new AtomicReference<>(previewBlockStreamInfo));
        given(postUpgradeContext.configuration()).willReturn(configWithCutover(false));

        assertThat(subject.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isFalse();
        assertThat(writableStates
                        .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                        .get())
                .isEqualTo(previewBlockStreamInfo);
    }

    @Test
    void skipsPostUpgradeSetupWhenCutoverAlreadyApplied() {
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var cutoverBlockStreamInfo = BlockStreamCutover.blockStreamInfoFrom(
                blockInfo,
                runningHashes,
                BlockStreamInfo.newBuilder()
                        .blockNumber(50)
                        .startOfBlockStateHash(HASH_OF_ZERO)
                        .build());
        final var writableStates = writableBlockStreamStates(new AtomicReference<>(cutoverBlockStreamInfo));
        givenCutoverContext(true, blockInfo, runningHashes);

        assertThat(subject.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isFalse();
        assertThat(writableStates
                        .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                        .get())
                .isEqualTo(cutoverBlockStreamInfo);
    }

    @Test
    void skipsPostUpgradeSetupAfterRealBlockStreamHasAdvanced() {
        final var blockInfo =
                validBlockInfo().copyBuilder().previewStreamOverwritten(true).build();
        final var advancedBlockStreamInfo = BlockStreamInfo.newBuilder()
                .blockNumber(blockInfo.lastBlockNumber() + 1)
                .startOfBlockStateHash(HASH_OF_ZERO)
                .build();
        final var writableStates = writableBlockStreamStates(new AtomicReference<>(advancedBlockStreamInfo));
        givenCutoverContext(true, blockInfo, validRunningHashes());

        assertThat(subject.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isFalse();
        assertThat(writableStates
                        .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                        .get())
                .isEqualTo(advancedBlockStreamInfo);
    }

    private void givenCutoverContext(
            final boolean enableCutover, final BlockInfo blockInfo, final RunningHashes runningHashes) {
        given(postUpgradeContext.configuration()).willReturn(configWithCutover(enableCutover));
        given(postUpgradeContext.readableStates(BlockRecordService.NAME))
                .willReturn(MapReadableStates.builder()
                        .state(new FunctionReadableSingletonState<>(
                                BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, () -> blockInfo))
                        .state(new FunctionReadableSingletonState<>(
                                RUNNING_HASHES_STATE_ID, RUNNING_HASHES_STATE_LABEL, () -> runningHashes))
                        .build());
    }

    private static MapWritableStates writableBlockStreamStates(
            final AtomicReference<BlockStreamInfo> blockStreamInfoRef) {
        return MapWritableStates.builder()
                .state(new FunctionWritableSingletonState<>(
                        BLOCK_STREAM_INFO_STATE_ID,
                        BLOCK_STREAM_INFO_STATE_LABEL,
                        blockStreamInfoRef::get,
                        blockStreamInfoRef::set))
                .build();
    }

    private static BlockInfo validBlockInfo() {
        return BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .blockHashes(Bytes.wrap(new byte[HASH_SIZE * 2]))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(1000, 0))
                .lastUsedConsTime(new Timestamp(1001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(1001, 0))
                .lastIntervalProcessTime(new Timestamp(1000, 0))
                .previewStreamOverwritten(false)
                .build();
    }

    private static RunningHashes validRunningHashes() {
        return new RunningHashes(HASH_A, HASH_B, HASH_C, HASH_D);
    }

    private static com.swirlds.config.api.Configuration configWithCutover(final boolean enableCutover) {
        return HederaTestConfigBuilder.create()
                .withValue("blockStream.enableCutover", enableCutover)
                .getOrCreateConfig();
    }
}
