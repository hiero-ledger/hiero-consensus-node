// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.records.BlockRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockRecordServiceTest {
    @Test
    void testGetServiceName() {
        BlockRecordService blockRecordService = new BlockRecordService();
        assertEquals(BlockRecordService.NAME, blockRecordService.getServiceName());
    }
}
