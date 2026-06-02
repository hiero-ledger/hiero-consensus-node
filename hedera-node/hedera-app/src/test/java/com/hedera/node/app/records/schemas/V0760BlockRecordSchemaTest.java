// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final V0760BlockRecordSchema subject = new V0760BlockRecordSchema();

    @Test
    void versionIsV0760() {
        assertEquals(new SemanticVersion(0, 76, 0, "", ""), subject.getVersion());
    }

    @Test
    void migrateIsNoopOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.migrate(ctx);

        verify(ctx, never()).appConfig();
        verifyNoInteractions(configuration, blockRecordStreamConfig);
    }

    @Test
    void migrateDeletesFileWhenPresent(@TempDir final Path tempDir) throws IOException {
        final var file = tempDir.resolve(DEFAULT_FILE_NAME);
        Files.writeString(file, "stale");
        assertTrue(Files.exists(file));
        givenUpgradeWithDir(tempDir.toString());

        subject.migrate(ctx);

        assertFalse(Files.exists(file));
    }

    @Test
    void migrateIsNoopWhenFileMissing(@TempDir final Path tempDir) {
        final var file = tempDir.resolve(DEFAULT_FILE_NAME);
        assertFalse(Files.exists(file));
        givenUpgradeWithDir(tempDir.toString());

        subject.migrate(ctx);

        assertFalse(Files.exists(file));
    }

    @Test
    void migrateSwallowsIoExceptionWhenDeleteFails(@TempDir final Path tempDir) throws IOException {
        // Create a directory at the file path. Files.deleteIfExists on a non-empty directory throws
        // DirectoryNotEmptyException (an IOException) — this exercises the catch branch.
        final var dirInsteadOfFile = tempDir.resolve(DEFAULT_FILE_NAME);
        Files.createDirectory(dirInsteadOfFile);
        Files.writeString(dirInsteadOfFile.resolve("blocker"), "x");
        givenUpgradeWithDir(tempDir.toString());

        subject.migrate(ctx);

        assertTrue(Files.exists(dirInsteadOfFile));
    }

    @Test
    void migrateLeavesFileAloneWhenFeatureFlagDisabled(@TempDir final Path tempDir) throws IOException {
        final var file = tempDir.resolve(DEFAULT_FILE_NAME);
        Files.writeString(file, "stale");
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.deleteStaleWrappedRecordHashesFile()).willReturn(false);

        subject.migrate(ctx);

        assertTrue(Files.exists(file));
        verify(blockRecordStreamConfig, never()).wrappedRecordHashesDir();
    }

    private void givenUpgradeWithDir(final String dir) {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(BlockRecordStreamConfig.class)).willReturn(blockRecordStreamConfig);
        given(blockRecordStreamConfig.deleteStaleWrappedRecordHashesFile()).willReturn(true);
        given(blockRecordStreamConfig.wrappedRecordHashesDir()).willReturn(dir);
    }
}
