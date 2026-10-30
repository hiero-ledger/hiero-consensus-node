// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockBufferSaturationInfo.SaturationDetail;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.CheckInfo;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.LiveBufferInfo;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.PruneConfig;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.PruneInfo;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService.PruneResult;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockBufferServiceLoggingTest {

    private static final MethodHandle maybeLogBufferUnhealthyTransitionHandle;

    static {
        try {
            final Class<BlockBufferService> cls = BlockBufferService.class;
            final Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());

            final Method maybeLogBufferUnhealthyTransition =
                    cls.getDeclaredMethod("maybeLogBufferUnhealthyTransition", PruneResult.class, PruneResult.class);
            maybeLogBufferUnhealthyTransition.setAccessible(true);
            maybeLogBufferUnhealthyTransitionHandle = lookup.unreflect(maybeLogBufferUnhealthyTransition);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockBufferService bufferService;
    private LogCaptor logCaptor;

    @BeforeEach
    void beforeEach() {
        final BlockStreamMetrics metrics = mock(BlockStreamMetrics.class);
        final BlockStreamingObs obs = mock(BlockStreamingObs.class);
        final ConfigProvider configProvider = mock(ConfigProvider.class);

        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));

        bufferService = new BlockBufferService(configProvider, metrics, obs);
        logCaptor = new LogCaptor(LogManager.getLogger(BlockBufferService.class));
    }

    @AfterEach
    void afterEach() {
        logCaptor.stopCapture();
    }

    @Test
    void testBufferUnhealthyLogging_healthyToHealthy() throws Throwable {
        invokeMaybeLogUnhealthyTransition(healthyPruneResult(), healthyPruneResult());
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void testBufferUnhealthyLogging_healthyToActionStage() throws Throwable {
        invokeMaybeLogUnhealthyTransition(healthyPruneResult(), actionStagePruneResult());
        final List<String> warnLogs = logCaptor.warnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.getFirst())
                .isEqualTo(
                        "Block buffer has become unhealthy (reason: unacked-blocks[Healthy->ActionStage] unacked-bytes[Healthy->ActionStage] in-progress-blocks[Healthy->ActionStage])");
    }

    @Test
    void testBufferUnhealthyLogging_healthyToSaturated() throws Throwable {
        invokeMaybeLogUnhealthyTransition(healthyPruneResult(), saturatedPruneResult());
        final List<String> warnLogs = logCaptor.warnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.getFirst())
                .isEqualTo(
                        "Block buffer has become unhealthy (reason: unacked-blocks[Healthy->Saturated] unacked-bytes[Healthy->Saturated] in-progress-blocks[Healthy->Saturated])");
    }

    @Test
    void testBufferUnhealthyLogging_actionStageToHealthy() throws Throwable {
        invokeMaybeLogUnhealthyTransition(actionStagePruneResult(), healthyPruneResult());
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void testBufferUnhealthyLogging_actionStageToActionStage() throws Throwable {
        invokeMaybeLogUnhealthyTransition(actionStagePruneResult(), actionStagePruneResult());
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void testBufferUnhealthyLogging_actionStageToSaturated() throws Throwable {
        invokeMaybeLogUnhealthyTransition(actionStagePruneResult(), saturatedPruneResult());
        final List<String> warnLogs = logCaptor.warnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.getFirst())
                .isEqualTo(
                        "Block buffer has become unhealthy (reason: unacked-blocks[ActionStage->Saturated] unacked-bytes[ActionStage->Saturated] in-progress-blocks[ActionStage->Saturated])");
    }

    @Test
    void testBufferUnhealthyLogging_saturatedToHealthy() throws Throwable {
        invokeMaybeLogUnhealthyTransition(saturatedPruneResult(), healthyPruneResult());
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void testBufferUnhealthyLogging_saturatedToActionStage() throws Throwable {
        invokeMaybeLogUnhealthyTransition(saturatedPruneResult(), actionStagePruneResult());
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void testBufferUnhealthyLogging_saturatedToSaturated() throws Throwable {
        invokeMaybeLogUnhealthyTransition(saturatedPruneResult(), saturatedPruneResult());
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    // Utilities

    void invokeMaybeLogUnhealthyTransition(final PruneResult previous, final PruneResult latest) throws Throwable {
        maybeLogBufferUnhealthyTransitionHandle.invoke(bufferService, previous, latest);
    }

    PruneResult healthyPruneResult() {
        return new PruneResult(
                Instant.now(),
                PruneConfig.NIL,
                CheckInfo.NIL,
                PruneInfo.NIL,
                LiveBufferInfo.NIL,
                new BlockBufferSaturationInfo(
                        Instant.now(),
                        new SaturationDetail(0.0D, false, 0, 100),
                        new SaturationDetail(0.0D, false, 0, 100),
                        new SaturationDetail(0.0D, false, 0, 100)));
    }

    PruneResult actionStagePruneResult() {
        return new PruneResult(
                Instant.now(),
                PruneConfig.NIL,
                CheckInfo.NIL,
                PruneInfo.NIL,
                LiveBufferInfo.NIL,
                new BlockBufferSaturationInfo(
                        Instant.now(),
                        new SaturationDetail(55.0D, true, 55, 100),
                        new SaturationDetail(55.0D, true, 55, 100),
                        new SaturationDetail(55.0D, true, 55, 100)));
    }

    PruneResult saturatedPruneResult() {
        return new PruneResult(
                Instant.now(),
                PruneConfig.NIL,
                CheckInfo.NIL,
                PruneInfo.NIL,
                LiveBufferInfo.NIL,
                new BlockBufferSaturationInfo(
                        Instant.now(),
                        new SaturationDetail(100.0D, true, 100, 100),
                        new SaturationDetail(100.0D, true, 100, 100),
                        new SaturationDetail(100.0D, true, 100, 100)));
    }
}
