// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrappedRecordFileBlockHashesDiskWriterTest extends AppTestBase {
    private static final String FILE_NAME = "wrapped-record-hashes.pb";

    @TempDir
    Path tmpDir;

    @Test
    void appendsRepeatedFieldOccurrencesThatParseBack() throws IOException, ParseException {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .build();

        final var writer = new WrappedRecordFileBlockHashesDiskWriter(app.configProvider(), FileSystems.getDefault());

        final var e0 =
                new WrappedRecordFileBlockHashes(0, Bytes.wrap(new byte[] {1, 2, 3}), Bytes.wrap(new byte[] {4}));
        final var e1 = new WrappedRecordFileBlockHashes(1, Bytes.wrap(new byte[] {5}), Bytes.wrap(new byte[] {6, 7}));

        writer.append(e0);
        writer.append(e1);

        final var file = tmpDir.resolve(FILE_NAME);
        assertTrue(Files.exists(file), "Expected file to be created");

        final var allBytes = Files.readAllBytes(file);
        final WrappedRecordFileBlockHashesLog log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                Bytes.wrap(allBytes).toReadableSequentialData(), false, false, 64, allBytes.length);

        assertEquals(List.of(e0, e1), log.entries(), "Expected entries to parse back from the on-disk file");
    }
}
