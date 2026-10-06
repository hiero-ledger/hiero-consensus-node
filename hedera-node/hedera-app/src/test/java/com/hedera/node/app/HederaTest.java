// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.impl.ClprServiceImpl;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.clpr.ClprRuntime;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.swirlds.base.time.Time;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import java.lang.reflect.Field;
import java.time.InstantSource;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Hedera} constructor, focused on verifying that services are correctly
 * built and registered on the {@link ServicesRegistry}.
 */
final class HederaTest {

    private FakeServicesRegistry servicesRegistry;
    private Hedera hedera;
    private HederaInjectionComponent app;
    private ClprRuntime clprRuntime;

    @BeforeEach
    void beforeEach() {
        servicesRegistry = new FakeServicesRegistry();
        final ServicesRegistry.Factory capturingFactory = (_, _) -> servicesRegistry;

        final var hintsService = mock(HintsService.class);
        when(hintsService.getServiceName()).thenReturn(HintsService.NAME);
        final var historyService = mock(HistoryService.class);
        when(historyService.getServiceName()).thenReturn(HistoryService.NAME);

        hedera = new Hedera(
                ConstructableRegistry.getInstance(),
                capturingFactory,
                new FakeServiceMigrator(),
                InstantSource.system(),
                NodeId.FIRST_NODE_ID,
                _ -> mock(StartupNetworks.class),
                (_, _, _, _, _) -> hintsService,
                (_, _, _) -> historyService,
                (_, _, _, _) -> mock(BlockHashSigner.class),
                HederaTestConfigBuilder.createConfig(),
                new FileSystemManager(),
                TestUtils.metrics(),
                Time.getCurrent());

        app = mock(HederaInjectionComponent.class, RETURNS_DEEP_STUBS);
        clprRuntime = mock(ClprRuntime.class);
        when(app.clprRuntime()).thenReturn(clprRuntime);
        setHederaField("daggerApp", app);
        setHederaField("platform", mock(Platform.class, RETURNS_DEEP_STUBS));
        setHederaField("configProvider", new ConfigProviderImpl());
    }

    @AfterEach
    void afterEach() {
        if (hedera != null
                && hedera.getStateLifecycleManager().getMutableState() instanceof VirtualMapStateImpl state) {
            state.close();
        }
    }

    @Test
    @DisplayName("Constructor builds a ClprService and registers it with sync/discoverEndpoints methods")
    void registersClprService() {
        final var clprService = serviceOfType(ClprServiceImpl.class);
        assertThat(clprService.getServiceName()).isEqualTo(ClprService.NAME);

        final Set<String> methodNames = clprService.rpcDefinitions().stream()
                .flatMap(def -> def.methods().stream())
                .map(RpcMethodDefinition::path)
                .collect(Collectors.toSet());
        assertThat(methodNames).contains("sync", "discoverEndpoints");
    }

    @Test
    @DisplayName("newPlatformStatus(ACTIVE) starts the CLPR runtime")
    void newPlatformStatusActiveStartsClprRuntime() {
        hedera.newPlatformStatus(PlatformStatus.ACTIVE);

        verify(clprRuntime).start();
        verify(clprRuntime, never()).stop();
    }

    @Test
    @DisplayName("newPlatformStatus(FREEZE_COMPLETE) stops the CLPR runtime")
    void newPlatformStatusFreezeCompleteStopsClprRuntime() {
        hedera.newPlatformStatus(PlatformStatus.FREEZE_COMPLETE);

        verify(clprRuntime).stop();
        verify(clprRuntime, never()).start();
    }

    @Test
    @DisplayName("newPlatformStatus(CATASTROPHIC_FAILURE) stops the CLPR runtime")
    void newPlatformStatusCatastrophicFailureStopsClprRuntime() {
        hedera.newPlatformStatus(PlatformStatus.CATASTROPHIC_FAILURE);

        verify(clprRuntime).stop();
        verify(clprRuntime, never()).start();
    }

    // As the Hedera class is not directly accessible for testing, this method provides a way to set private fields.
    // Although this is not ideal, it is the only way to access private fields and configure them in a test environment.
    private void setHederaField(final String name, final Object value) {
        try {
            final Field field = Hedera.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(hedera, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set field '" + name + "' on Hedera", e);
        }
    }

    private <T extends Service> T serviceOfType(final Class<T> type) {
        return servicesRegistry.registrations().stream()
                .map(ServicesRegistry.Registration::service)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No service of type " + type.getSimpleName() + " registered"));
    }
}
