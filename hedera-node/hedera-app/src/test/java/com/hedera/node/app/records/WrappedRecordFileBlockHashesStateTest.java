// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.node.app.records.schemas.V0720BlockRecordSchema.WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.node.app.fixtures.AppTestBase;
import com.swirlds.platform.state.service.PlatformStateService;
import org.junit.jupiter.api.Test;

class WrappedRecordFileBlockHashesStateTest extends AppTestBase {
    @Test
    void schemaCreatesQueueState() {
        final var app = appBuilder()
                .withService(new BlockRecordService())
                .withService(PlatformStateService.PLATFORM_STATE_SERVICE)
                .build();

        final var state = app.workingStateAccessor().getState();
        assertNotNull(state);

        final var writableStates = state.getWritableStates(BlockRecordService.NAME);
        assertNotNull(writableStates.getQueue(WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID));
    }
}
