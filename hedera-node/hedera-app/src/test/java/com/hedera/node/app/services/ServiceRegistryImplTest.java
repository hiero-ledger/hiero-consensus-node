// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.spi.fixtures.TestService;
import org.hiero.base.constructable.ConstructableRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class ServicesRegistryImplTest {

    @Mock
    ConstructableRegistry cr;

    @DisplayName("The constructable registry cannot be null")
    @Test
    void nullConstructableRegistryThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new ServicesRegistryImpl(null, DEFAULT_CONFIG))
                .isInstanceOf(NullPointerException.class);
    }

    @DisplayName("The genesis record builder cannot be null")
    @Test
    void nullGenesisRecordsThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new ServicesRegistryImpl(null, DEFAULT_CONFIG))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registrationsAreSortedByName() {
        final var registry = new ServicesRegistryImpl(cr, DEFAULT_CONFIG);
        registry.register(TestService.newBuilder().name("B").build());
        registry.register(TestService.newBuilder().name("C").build());
        registry.register(TestService.newBuilder().name("A").build());

        final var registrations = registry.registrations();
        assertThat(registrations.stream().map(r -> r.service().getServiceName()))
                .containsExactly("A", "B", "C");
    }

    @Test
    void subRegistryContainsOnlyRequestedServices() {
        final var registry = new ServicesRegistryImpl(cr, DEFAULT_CONFIG);
        registry.register(TestService.newBuilder().name("A").build());
        registry.register(TestService.newBuilder().name("B").build());
        registry.register(TestService.newBuilder().name("C").build());

        final var subRegistry = registry.subRegistryFor("A", "C");
        assertThat(subRegistry.registrations().stream().map(r -> r.service().getServiceName()))
                .containsExactly("A", "C");
    }
}
