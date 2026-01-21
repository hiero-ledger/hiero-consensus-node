// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.records.BlockRecordService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
final class BlockRecordServiceTest {
    private @Mock SchemaRegistry schemaRegistry;
    private @Mock MigrationContext migrationContext;
    private @Mock WritableSingletonState runningHashesState;
    private @Mock WritableSingletonState blockInfoState;
    private @Mock WritableStates writableStates;

    @Test
    void testGetServiceName() {
        BlockRecordService blockRecordService = new BlockRecordService();
        assertEquals(BlockRecordService.NAME, blockRecordService.getServiceName());
    }
}
