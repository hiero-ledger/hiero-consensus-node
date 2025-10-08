// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl.test;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.roster.RosterTransplantSchema;
import com.hedera.node.app.service.roster.impl.RosterServiceImpl;
import com.hedera.node.app.service.roster.impl.schemas.V0540RosterSchema;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterServiceImplTest {
    @Mock
    private Predicate<Roster> canAdopt;

    @Mock
    private Supplier<State> stateSupplier;

    @Mock
    private BiConsumer<Roster, Roster> onAdopt;

    private RosterServiceImpl rosterService;

    @BeforeEach
    void setUp() {
        rosterService = new RosterServiceImpl(
                canAdopt, onAdopt, stateSupplier, TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE);
    }

    @Test
    void registerSchemasNullArgsThrow() {
        Assertions.assertThatThrownBy(() -> rosterService.registerSchemas(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerExpectedSchemas() {
        final var schemaRegistry = Mockito.mock(SchemaRegistry.class);

        rosterService.registerSchemas(schemaRegistry);
        final var captor = ArgumentCaptor.forClass(Schema.class);
        Mockito.verify(schemaRegistry).register(captor.capture());
        final var schemas = captor.getAllValues();
        Assertions.assertThat(schemas).hasSize(1);
        Assertions.assertThat(schemas.getFirst()).isInstanceOf(V0540RosterSchema.class);
        Assertions.assertThat(schemas.getFirst()).isInstanceOf(RosterTransplantSchema.class);
    }

    @Test
    void testServiceNameReturnsCorrectName() {
        Assertions.assertThat(rosterService.getServiceName()).isEqualTo(RosterService.NAME);
    }
}
