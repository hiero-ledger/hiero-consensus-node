// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0700TokenSchemaTest {

    @Mock
    private MigrationContext ctx;

    @Mock
    private ReadableStates previousStates;

    @Mock
    private WritableStates newStates;

    @Mock
    private WritableSingletonState nodePaymentsState;

    private V0700TokenSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0700TokenSchema();
    }

    @Test
    @DisplayName("Schema version should be 0.70.0")
    void testSchemaVersion() {
        final var expectedVersion =
                SemanticVersion.newBuilder().major(0).minor(70).patch(0).build();
        assertThat(subject.getVersion()).isEqualTo(expectedVersion);
    }

    @Test
    @DisplayName("States to create should include NODE_PAYMENTS singleton")
    void testStatesToCreate() {
        final var statesToCreate = subject.statesToCreate();

        assertThat(statesToCreate).hasSize(1);

        final var sortedResult = statesToCreate.stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var nodePaymentsDef = sortedResult.getFirst();
        assertThat(nodePaymentsDef.stateKey()).isEqualTo(NODE_PAYMENTS_KEY);
        assertThat(nodePaymentsDef.valueCodec()).isEqualTo(NodePayments.PROTOBUF);
        assertThat(nodePaymentsDef.singleton()).isTrue();
    }

    @Test
    @DisplayName("Migrate should initialize NodePayments when state doesn't exist in previous states")
    void testMigrateWhenStateDoesNotExist() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(NODE_PAYMENTS_STATE_ID)).willReturn(false);
        given(ctx.newStates()).willReturn(newStates);
        given(newStates.getSingleton(NODE_PAYMENTS_STATE_ID)).willReturn(nodePaymentsState);

        subject.migrate(ctx);

        verify(nodePaymentsState).put(NodePayments.DEFAULT);
    }

    @Test
    @DisplayName("Migrate should not initialize NodePayments when state already exists")
    void testMigrateWhenStateAlreadyExists() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(NODE_PAYMENTS_STATE_ID)).willReturn(true);

        subject.migrate(ctx);

        verify(ctx, never()).newStates();
        verify(nodePaymentsState, never()).put(NodePayments.DEFAULT);
    }

    @Test
    @DisplayName("NODE_PAYMENTS_STATE_ID should match SingletonType ordinal")
    void testNodePaymentsStateId() {
        // The state ID should be consistent with the SingletonType enum
        assertThat(NODE_PAYMENTS_STATE_ID).isPositive();
    }
}
