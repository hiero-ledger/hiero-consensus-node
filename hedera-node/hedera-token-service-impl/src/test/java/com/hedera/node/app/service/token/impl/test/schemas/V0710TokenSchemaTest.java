// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.NATIVE_COIN_DECIMALS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.NATIVE_COIN_DECIMALS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.NativeCoinDecimals;
import com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.data.NativeCoinConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class V0710TokenSchemaTest {

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private MigrationContext<SemanticVersion> ctx;

    @Mock
    private ReadableStates previousStates;

    @Mock
    private ReadableSingletonState<NativeCoinDecimals> nativeCoinDecimalsState;

    @Mock
    private Configuration appConfig;

    @Mock
    private NativeCoinConfig nativeCoinConfig;

    @LoggingSubject
    private V0710TokenSchema subject = new V0710TokenSchema();

    @Test
    @DisplayName("Schema version should be 0.71.0")
    void testSchemaVersion() {
        final var expectedVersion =
                SemanticVersion.newBuilder().major(0).minor(71).patch(0).build();
        assertThat(subject.getVersion()).isEqualTo(expectedVersion);
    }

    @Test
    @DisplayName("States to create should include NATIVE_COIN_DECIMALS singleton")
    void testStatesToCreate() {
        final var statesToCreate = subject.statesToCreate();

        assertThat(statesToCreate).hasSize(1);

        final var sortedResult = statesToCreate.stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var nativeCoinDecimalsDef = sortedResult.getFirst();
        assertThat(nativeCoinDecimalsDef.stateKey()).isEqualTo(NATIVE_COIN_DECIMALS_KEY);
        assertThat(nativeCoinDecimalsDef.valueCodec()).isEqualTo(NativeCoinDecimals.PROTOBUF);
        assertThat(nativeCoinDecimalsDef.singleton()).isTrue();
    }

    @Test
    @DisplayName("NATIVE_COIN_DECIMALS_STATE_ID should match SingletonType ordinal")
    void testNativeCoinDecimalsStateId() {
        assertThat(NATIVE_COIN_DECIMALS_STATE_ID).isEqualTo(56);
    }

    @Test
    @DisplayName("Migrate at genesis should return without throwing or accessing previousStates")
    void testMigrateAtGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.migrate(ctx);

        verifyNoInteractions(previousStates);
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    @DisplayName("Migrate on non-genesis with missing singleton should throw IllegalStateException")
    void testMigrateWhenStateMissing() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(NATIVE_COIN_DECIMALS_STATE_ID)).willReturn(false);

        assertThatThrownBy(() -> subject.migrate(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NATIVE_COIN_DECIMALS");
    }

    @Test
    @DisplayName("Migrate on non-genesis with null singleton value should throw IllegalStateException")
    void testMigrateWhenStateValueNull() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(NATIVE_COIN_DECIMALS_STATE_ID)).willReturn(true);
        given(previousStates.<NativeCoinDecimals>getSingleton(NATIVE_COIN_DECIMALS_STATE_ID))
                .willReturn(nativeCoinDecimalsState);
        given(nativeCoinDecimalsState.get()).willReturn(null);

        assertThatThrownBy(() -> subject.migrate(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NATIVE_COIN_DECIMALS");
    }

    @Test
    @DisplayName("Migrate on non-genesis with matching config and state should not warn")
    void testMigrateWhenDecimalsMatch() {
        // given — persisted decimals = 6, config decimals = 6
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(NATIVE_COIN_DECIMALS_STATE_ID)).willReturn(true);
        given(previousStates.<NativeCoinDecimals>getSingleton(NATIVE_COIN_DECIMALS_STATE_ID))
                .willReturn(nativeCoinDecimalsState);
        given(nativeCoinDecimalsState.get())
                .willReturn(NativeCoinDecimals.newBuilder().decimals(6).build());
        given(ctx.appConfig()).willReturn(appConfig);
        given(appConfig.getConfigData(NativeCoinConfig.class)).willReturn(nativeCoinConfig);
        given(nativeCoinConfig.decimals()).willReturn(6);

        // when — should complete without throwing or warning
        subject.migrate(ctx);

        // then — no warning logged
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    @DisplayName("Migrate on non-genesis with mismatched config should warn but not throw")
    void testMigrateWhenDecimalsMismatch() {
        // given — persisted decimals = 6, config decimals = 8
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.contains(NATIVE_COIN_DECIMALS_STATE_ID)).willReturn(true);
        given(previousStates.<NativeCoinDecimals>getSingleton(NATIVE_COIN_DECIMALS_STATE_ID))
                .willReturn(nativeCoinDecimalsState);
        given(nativeCoinDecimalsState.get())
                .willReturn(NativeCoinDecimals.newBuilder().decimals(6).build());
        given(ctx.appConfig()).willReturn(appConfig);
        given(appConfig.getConfigData(NativeCoinConfig.class)).willReturn(nativeCoinConfig);
        given(nativeCoinConfig.decimals()).willReturn(8);

        // when — should complete without throwing (mismatch logs warning, does not fail)
        subject.migrate(ctx);

        // then — warning should be logged with both config and persisted values
        assertThat(logCaptor.warnLogs()).anyMatch(msg -> msg.contains("8") && msg.contains("6"));
    }
}
