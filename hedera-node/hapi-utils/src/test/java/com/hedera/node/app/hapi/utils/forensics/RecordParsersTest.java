// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordParsersTest {
    @TempDir
    Path directory;

    @Test
    void failsInsteadOfReturningPartialResultsWhenARecordFileIsCorrupt() throws IOException {
        Files.write(directory.resolve("2022-12-05T14_23_46.192841556Z.rcd"), new byte[] {0, 0, 0, 6});
        final var corruptFile = directory.resolve("2022-12-05T14_23_47.192841556Z.rcd.gz");
        Files.writeString(corruptFile, "not a gzip stream");

        assertThatThrownBy(() -> RecordParsers.parseV6RecordStreamEntriesIn(directory.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(corruptFile.getFileName().toString())
                .hasCauseInstanceOf(IOException.class);
    }
}
