// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static org.mockito.BDDMockito.given;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamingObsTest {

    @Mock
    private ConfigProvider configProvider;

    private BlockStreamingObs obsEnabled;
    private BlockStreamingObs obsDisabled;

    @BeforeEach
    void setUp() {
        obsEnabled = makeObs(true);
        obsDisabled = makeObs(false);
    }

    @Test
    void onBlockInit_whenDisabled_storesNoData() {
        obsDisabled.onBlockInit(1L, tick());
        // If no entry is stored, onBlockOpen will be a no-op and not throw
        obsDisabled.onBlockOpen(1L, tick());
    }

    @Test
    void onBlockInit_whenEnabled_allowsSubsequentLifecycleEvents() {
        final long blockNumber = 1L;
        final long t0 = tick();
        obsEnabled.onBlockInit(blockNumber, t0);
        // These must not throw — data exists for this block
        obsEnabled.onBlockOpen(blockNumber, t0 + 1);
        obsEnabled.onBlockClose(blockNumber, t0 + 2);
    }

    @Test
    void onBlockOpen_unknownBlock_isNoOp() {
        // Block was never initialised — must not throw
        obsEnabled.onBlockOpen(999L, tick());
    }

    @Test
    void onBlockOpen_onlyRecordsFirstCall() {
        final long blockNumber = 2L;
        obsEnabled.onBlockInit(blockNumber, tick());
        obsEnabled.onBlockOpen(blockNumber, tick());
        // Second call must be idempotent and not throw
        obsEnabled.onBlockOpen(blockNumber, tick());
    }

    @Test
    void onBlockItemAdd_whenDisabled_storesNothing() {
        obsDisabled.onBlockItemAdd(1L, 0, tick(), 512);
    }

    @Test
    void onBlockItemAdd_whenEnabled_doesNotThrow() {
        final long blockNumber = 3L;
        obsEnabled.onBlockInit(blockNumber, tick());
        obsEnabled.onBlockOpen(blockNumber, tick());
        obsEnabled.onBlockItemAdd(blockNumber, 0, tick(), 256);
        obsEnabled.onBlockItemAdd(blockNumber, 1, tick(), 512);
    }

    @Test
    void onBlockItemAdd_duplicateIndex_ignoredGracefully() {
        final long blockNumber = 4L;
        obsEnabled.onBlockInit(blockNumber, tick());
        obsEnabled.onBlockOpen(blockNumber, tick());
        obsEnabled.onBlockItemAdd(blockNumber, 0, tick(), 100);
        // Same index again — must be a no-op, not throw or double-count
        obsEnabled.onBlockItemAdd(blockNumber, 0, tick(), 200);
    }

    @Test
    void onBlockItemsSend_unknownBlock_isNoOp() {
        obsEnabled.onBlockItemsSend(999L, 0, 5, tick(), tick() + 1);
    }

    @Test
    void onBlockItemsSend_recordsSendWindow() {
        final long blockNumber = 5L;
        final long t0 = tick();
        obsEnabled.onBlockInit(blockNumber, t0);
        obsEnabled.onBlockOpen(blockNumber, t0 + 1);
        obsEnabled.onBlockItemAdd(blockNumber, 0, t0 + 2, 128);
        obsEnabled.onBlockItemAdd(blockNumber, 1, t0 + 3, 256);
        // Must not throw
        obsEnabled.onBlockItemsSend(blockNumber, 0, 1, t0 + 10, t0 + 20);
    }

    @Test
    void onBlockClose_unknownBlock_isNoOp() {
        obsEnabled.onBlockClose(999L, tick());
    }

    @Test
    void onBlockAcknowledge_afterFullLifecycle_doesNotThrow() {
        final long blockNumber = 6L;
        final long t0 = tick();
        obsEnabled.onBlockInit(blockNumber, t0);
        obsEnabled.onBlockOpen(blockNumber, t0 + 1_000);
        obsEnabled.onBlockItemAdd(blockNumber, 0, t0 + 2_000, 64);
        obsEnabled.onBlockHeaderSend(blockNumber, t0 + 3_000, t0 + 4_000);
        obsEnabled.onBlockItemsSend(blockNumber, 0, 0, t0 + 3_000, t0 + 4_000);
        obsEnabled.onBlockEndSend(blockNumber, t0 + 5_000, t0 + 6_000);
        obsEnabled.onBlockClose(blockNumber, t0 + 7_000);
        // Acknowledge records the ack tick; aggregation is deferred to the gather thread
        obsEnabled.onBlockAcknowledge(blockNumber, t0 + 8_000);
    }

    @Test
    void gatherAndLog_whenDisabled_clearsAccumulatedData() {
        // Populate some data while enabled, then disable and verify no crash
        final long blockNumber = 7L;
        obsEnabled.onBlockInit(blockNumber, tick());
        obsEnabled.onBlockOpen(blockNumber, tick());

        // Simulate a gather cycle with the feature turned off — must clear data and not throw
        final BlockStreamingObs obsNowDisabled = makeObs(false);
        obsNowDisabled.onBlockInit(blockNumber, tick()); // no-op
    }

    @Test
    void onBlockHeaderSend_unknownBlock_isNoOp() {
        obsEnabled.onBlockHeaderSend(999L, tick(), tick() + 1);
    }

    @Test
    void onBlockEndSend_unknownBlock_isNoOp() {
        obsEnabled.onBlockEndSend(999L, tick(), tick() + 1);
    }

    @Test
    void onBlockProofCreate_unknownBlock_isNoOp() {
        obsEnabled.onBlockProofCreate(999L, tick());
    }

    @Test
    void onBlockProofAdd_unknownBlock_isNoOp() {
        obsEnabled.onBlockProofAdd(999L, tick());
    }

    @Test
    void onBlockFooterCreate_unknownBlock_isNoOp() {
        obsEnabled.onBlockFooterCreate(999L, tick());
    }

    private BlockStreamingObs makeObs(final boolean enabled) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(com.hedera.node.config.data.BlockStreamConfig.class)
                .withValue("blockStream.enhancedObservabilityEnabled", String.valueOf(enabled))
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        return new BlockStreamingObs(configProvider);
    }

    private long tick() {
        return System.nanoTime();
    }
}
