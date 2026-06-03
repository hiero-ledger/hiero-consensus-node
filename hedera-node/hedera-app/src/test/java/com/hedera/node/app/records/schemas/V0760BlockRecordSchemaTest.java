// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0760BlockRecordSchemaTest {
    @Mock
    private MigrationContext<SemanticVersion> ctx;

    @Mock
    private Configuration configuration;

    @Mock
    private BlockRecordStreamConfig blockRecordStreamConfig;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private BlockStreamJumpstartConfig blockStreamJumpstartConfig;

    @Mock
    private VersionConfig versionConfig;

    @Mock
    private HederaConfig hederaConfig;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<BlockInfo> blockInfoState;

    private final V0760BlockRecordSchema subject = new V0760BlockRecordSchema();

    @Test
    void versionIsV0760() {
        assertEquals(new SemanticVersion(0, 76, 0, "", ""), subject.getVersion());
    }

    @Test
    void restartIsNoopOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verify(ctx, never()).appConfig();
        verifyNoInteractions(configuration, blockRecordStreamConfig, writableStates, blockInfoState);
    }

    @Test
    void restartReturnsEarlyWhenBlockInfoSingletonIsNullOnUpgrade() {
        givenRestartUpgradePreconditions();
        givenLiveWriteEnabled();
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(null);

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
        verify(ctx, never()).sharedValues();
        verify(blockRecordStreamConfig, never()).writeWrappedRecordFileBlockHashesToDisk();
    }

    @Test
    void restartSkipsVotingWhenNotUpgrade() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(VersionConfig.class)).willReturn(versionConfig);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(false);
        given(versionConfig.servicesVersion()).willReturn(new SemanticVersion(0, 75, 0, "", ""));
        given(hederaConfig.configVersion()).willReturn(0);
        given(ctx.isUpgrade(any())).willReturn(false);
        givenWriteFlagDisabled("/tmp/whatever");

        subject.restart(ctx);

        verifyNoInteractions(blockInfoState);
    }

    @Test
    void restartReinitializesVotingFieldsWhenJumpstartEnabled() {
        givenRestartUpgradePreconditions();
        givenLiveWriteEnabled();
        givenCutoverDisabled();
        givenWriteFlagDisabled("/tmp/whatever");
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(1L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(baseBlockInfo()
                        .copyBuilder()
                        .votingCompletionDeadlineBlockNumber(123)
                        .votingComplete(false)
                        .build());

        subject.restart(ctx);

        verify(blockInfoState)
                .put(baseBlockInfo()
                        .copyBuilder()
                        .votingComplete(false)
                        .votingCompletionDeadlineBlockNumber(baseBlockInfo().lastBlockNumber() + 10)
                        .migrationRootHashVotes(List.of())
                        .build());
    }

    @Test
    void restartSkipsVotingInitWhenJumpstartNotPositive() {
        givenRestartUpgradePreconditions();
        givenLiveWriteEnabled();
        givenCutoverDisabled();
        givenWriteFlagDisabled("/tmp/whatever");
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(baseBlockInfo());

        subject.restart(ctx);

        verify(blockInfoState, never()).put(any());
    }

    @Test
    void restartSkipsVotingInitWhenLiveWriteDisabled() {
        givenRestartUpgradePreconditions();
        given(blockRecordStreamConfig.liveWritePrevWrappedRecordHashes()).willReturn(false);
        givenCutoverDisabled();
        givenWriteFlagDisabled("/tmp/whatever");

        subject.restart(ctx);

        verifyNoInteractions(blockInfoState);
    }

    @Test
    void restartSharesBlockInfoAndRunningHashesWhenCutoverEnabled() {
        givenRestartUpgradePreconditions();
        givenLiveWriteEnabled();
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(true);
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        givenWriteFlagEnabled();
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        final var blockInfo = baseBlockInfo();
        given(blockInfoState.get()).willReturn(blockInfo);
        final var runningHashes = RunningHashes.DEFAULT;
        @SuppressWarnings("unchecked")
        final WritableSingletonState<RunningHashes> runningHashesState =
                (WritableSingletonState<RunningHashes>) org.mockito.Mockito.mock(WritableSingletonState.class);
        doReturn(runningHashesState).when(writableStates).getSingleton(RUNNING_HASHES_STATE_ID);
        given(runningHashesState.get()).willReturn(runningHashes);
        final Map<String, Object> sharedValues = new HashMap<>();
        given(ctx.sharedValues()).willReturn(sharedValues);

        subject.restart(ctx);

        assertSame(blockInfo, sharedValues.get("SHARED_BLOCK_RECORD_INFO"));
        assertSame(runningHashes, sharedValues.get("SHARED_RUNNING_HASHES"));
    }

    @Test
    void restartDoesNotShareValuesWhenCutoverDisabled() {
        givenRestartUpgradePreconditions();
        givenLiveWriteEnabled();
        givenCutoverDisabled();
        givenWriteFlagDisabled("/tmp/whatever");
        given(configuration.getConfigData(BlockStreamJumpstartConfig.class)).willReturn(blockStreamJumpstartConfig);
        given(blockStreamJumpstartConfig.blockNum()).willReturn(0L);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get()).willReturn(baseBlockInfo());

        subject.restart(ctx);

        verify(ctx, never()).sharedValues();
    }

    @Test
    void restartDeletesFileWhenWriteFlagDisabledAndFilePresent(@TempDir final Path tempDir) throws IOException {
        final var file = tempDir.resolve(DEFAULT_FILE_NAME);
        Files.writeString(file, "stale");
        assertTrue(Files.exists(file));
        givenRestartNonUpgrade();
        givenCutoverDisabled();
        givenWriteFlagDisabled(tempDir.toString());

        subject.restart(ctx);

        assertFalse(Files.exists(file));
    }

    @Test
    void restartIsNoopWhenFileMissing(@TempDir final Path tempDir) {
        final var file = tempDir.resolve(DEFAULT_FILE_NAME);
        assertFalse(Files.exists(file));
        givenRestartNonUpgrade();
        givenCutoverDisabled();
        givenWriteFlagDisabled(tempDir.toString());

        subject.restart(ctx);

        assertFalse(Files.exists(file));
    }

    @Test
    void restartSwallowsIoExceptionWhenDeleteFails(@TempDir final Path tempDir) throws IOException {
        // Create a directory at the file path. Files.deleteIfExists on a non-empty directory throws
        // DirectoryNotEmptyException (an IOException) — this exercises the catch branch.
        final var dirInsteadOfFile = tempDir.resolve(DEFAULT_FILE_NAME);
        Files.createDirectory(dirInsteadOfFile);
        Files.writeString(dirInsteadOfFile.resolve("blocker"), "x");
        givenRestartNonUpgrade();
        givenCutoverDisabled();
        givenWriteFlagDisabled(tempDir.toString());

        subject.restart(ctx);

        assertTrue(Files.exists(dirInsteadOfFile));
    }

    @Test
    void restartLeavesFileAloneWhenWriteFlagEnabled(@TempDir final Path tempDir) throws IOException {
        final var file = tempDir.resolve(DEFAULT_FILE_NAME);
        Files.writeString(file, "stale");
        givenRestartNonUpgrade();
        givenCutoverDisabled();
        givenWriteFlagEnabled();

        subject.restart(ctx);

        assertTrue(Files.exists(file));
        verify(blockRecordStreamConfig, never()).wrappedRecordHashesDir();
    }

    private void givenRestartUpgradePreconditions() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(VersionConfig.class)).willReturn(versionConfig);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(versionConfig.servicesVersion()).willReturn(new SemanticVersion(0, 75, 0, "", ""));
        given(hederaConfig.configVersion()).willReturn(0);
        given(ctx.isUpgrade(any())).willReturn(true);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
    }

    private void givenRestartNonUpgrade() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(VersionConfig.class)).willReturn(versionConfig);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(versionConfig.servicesVersion()).willReturn(new SemanticVersion(0, 76, 0, "", ""));
        given(hederaConfig.configVersion()).willReturn(0);
        given(ctx.isUpgrade(any())).willReturn(false);
    }

    private void givenLiveWriteEnabled() {
        given(blockRecordStreamConfig.liveWritePrevWrappedRecordHashes()).willReturn(true);
    }

    private void givenCutoverDisabled() {
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.enableCutover()).willReturn(false);
    }

    private void givenWriteFlagEnabled() {
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.writeWrappedRecordFileBlockHashesToDisk()).willReturn(true);
    }

    private void givenWriteFlagDisabled(final String dir) {
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.writeWrappedRecordFileBlockHashesToDisk()).willReturn(false);
        given(blockRecordStreamConfig.wrappedRecordHashesDir()).willReturn(dir);
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
                .build();
    }
}
