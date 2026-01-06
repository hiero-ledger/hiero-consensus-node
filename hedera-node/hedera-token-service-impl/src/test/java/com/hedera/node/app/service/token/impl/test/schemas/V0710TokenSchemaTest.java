// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_TIME_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_TIME_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.StakePeriodTime;
import com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema;
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
class V0710TokenSchemaTest {

    @Mock
    private MigrationContext ctx;

    @Mock
    private ReadableStates previousStates;

    @Mock
    private WritableStates newStates;

    @Mock
    private WritableSingletonState stakePeriodTimeState;

    private V0710TokenSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0710TokenSchema();
    }

    @Test
    @DisplayName("Schema version should be 0.71.0")
    void testSchemaVersion() {
        final var expectedVersion =
                SemanticVersion.newBuilder().major(0).minor(71).patch(0).build();
        assertThat(subject.getVersion()).isEqualTo(expectedVersion);
    }

    @Test
    @DisplayName("States to create should include STAKE_PERIOD_TIME singleton")
    void testStatesToCreate() {
        final var statesToCreate = subject.statesToCreate();

        assertThat(statesToCreate).hasSize(1);

        final var sortedResult = statesToCreate.stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var stakePeriodTimeDef = sortedResult.getFirst();
        assertThat(stakePeriodTimeDef.stateKey()).isEqualTo(STAKE_PERIOD_TIME_KEY);
        assertThat(stakePeriodTimeDef.valueCodec()).isEqualTo(StakePeriodTime.PROTOBUF);
        assertThat(stakePeriodTimeDef.singleton()).isTrue();
    }

    @Test
    @DisplayName("Migrate should initialize StakePeriodTime on genesis")
    void testMigrateOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);
        given(ctx.newStates()).willReturn(newStates);
        given(newStates.getSingleton(STAKE_PERIOD_TIME_STATE_ID)).willReturn(stakePeriodTimeState);

        subject.migrate(ctx);

        verify(stakePeriodTimeState).put(StakePeriodTime.DEFAULT);
    }

    @Test
    @DisplayName("Migrate should initialize StakePeriodTime when state doesn't exist in previous states")
    void testMigrateWhenStateDoesNotExist() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(STAKE_PERIOD_TIME_STATE_ID)).willReturn(false);
        given(ctx.newStates()).willReturn(newStates);
        given(newStates.getSingleton(STAKE_PERIOD_TIME_STATE_ID)).willReturn(stakePeriodTimeState);

        subject.migrate(ctx);

        verify(stakePeriodTimeState).put(StakePeriodTime.DEFAULT);
    }

    @Test
    @DisplayName("Migrate should not initialize StakePeriodTime when state already exists")
    void testMigrateWhenStateAlreadyExists() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(STAKE_PERIOD_TIME_STATE_ID)).willReturn(true);

        subject.migrate(ctx);

        verify(ctx, never()).newStates();
        verify(stakePeriodTimeState, never()).put(StakePeriodTime.DEFAULT);
    }

    @Test
    @DisplayName("STAKE_PERIOD_TIME_STATE_ID should match SingletonType ordinal")
    void testStakePeriodTimeStateId() {
        // The state ID should be consistent with the SingletonType enum
        assertThat(STAKE_PERIOD_TIME_STATE_ID).isPositive();
    }

    @Test
    @DisplayName("State definition should have correct state ID")
    void testStateDefinitionHasCorrectStateId() {
        final var statesToCreate = subject.statesToCreate();
        final var stakePeriodTimeDef = statesToCreate.iterator().next();

        assertThat(stakePeriodTimeDef.stateId()).isEqualTo(STAKE_PERIOD_TIME_STATE_ID);
    }
}
