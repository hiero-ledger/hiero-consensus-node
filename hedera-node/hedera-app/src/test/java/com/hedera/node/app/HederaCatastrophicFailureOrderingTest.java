// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.cloud.uploader.IssDetectionUploadCoordinator;
import com.hedera.node.app.blocks.cloud.uploader.TriageBlockUploadCoordinator;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.platform.system.Platform;
import java.lang.reflect.Field;
import java.time.InstantSource;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the ordering inside {@link Hedera#newPlatformStatus} for {@code CATASTROPHIC_FAILURE}: the
 * block-stream triage flush ({@link BlockStreamManager#notifyFatalEvent()} then
 * {@link BlockStreamManager#awaitFatalShutdown}) must run BEFORE {@link BlockNodeConnectionManager#shutdown()}, whose
 * {@code BlockBufferService.shutdown()} clears the in-memory buffer the gRPC writer flushes the open block from. If
 * that shutdown ran first the open block would be silently lost in {@code writerMode=GRPC}.
 *
 * <p>{@code Hedera} has no test seam, so this uses a {@code CALLS_REAL_METHODS} partial mock with its
 * collaborators injected by reflection — just enough to drive {@code newPlatformStatus(CATASTROPHIC_FAILURE)} and
 * assert the call order with {@link InOrder}.
 */
@ExtendWith(MockitoExtension.class)
class HederaCatastrophicFailureOrderingTest {

    @Mock
    private HederaInjectionComponent daggerApp;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private TriageBlockUploadCoordinator triageBlockUploadCoordinator;

    @Mock
    private IssDetectionUploadCoordinator issDetectionUploadCoordinator;

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private TransactionPoolNexus transactionPool;

    @Mock
    private Platform platform;

    @Mock
    private ConfigProviderImpl configProvider;

    @Mock
    private AppContext appContext;

    @Test
    void flushesOpenBlocksBeforeShuttingDownBlockNodeConnectionsOnCatastrophicFailure() throws Exception {
        final var hedera = mock(Hedera.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        given(daggerApp.blockStreamManager()).willReturn(blockStreamManager);
        given(daggerApp.blockNodeConnectionManager()).willReturn(blockNodeConnectionManager);
        given(daggerApp.quiescenceController()).willReturn(quiescenceController);
        given(daggerApp.triageBlockUploadCoordinator()).willReturn(triageBlockUploadCoordinator);
        given(daggerApp.issDetectionUploadCoordinator()).willReturn(issDetectionUploadCoordinator);

        // streamToBlockNodes() is true whenever writerMode != FILE, so the connection shutdown is reached.
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        // isNotEmbedded() compares the app context's instant source by reference to InstantSource.system(); match it so
        // the embedded guard does not skip the connection shutdown whose order we are asserting.
        given(appContext.instantSource()).willReturn(InstantSource.system());

        // shutdownGrpcServer() is a real, unrelated method on this path; neutralize it (do*-form avoids invoking it).
        doNothing().when(hedera).shutdownGrpcServer();

        setField(hedera, "daggerApp", daggerApp);
        setField(hedera, "configProvider", configProvider);
        setField(hedera, "platform", platform);
        setField(hedera, "transactionPool", transactionPool);
        setField(hedera, "appContext", appContext);

        hedera.newPlatformStatus(CATASTROPHIC_FAILURE);

        final InOrder inOrder = inOrder(blockStreamManager, blockNodeConnectionManager);
        inOrder.verify(blockStreamManager).notifyFatalEvent();
        inOrder.verify(blockStreamManager).awaitFatalShutdown(any());
        inOrder.verify(blockNodeConnectionManager).shutdown();
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = Hedera.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
