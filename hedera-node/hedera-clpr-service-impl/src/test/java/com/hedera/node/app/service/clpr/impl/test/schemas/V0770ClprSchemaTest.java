// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.schemas;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0770ClprSchemaTest {

    @Mock
    private MigrationContext<SemanticVersion> migrationContext;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ClprLedgerConfiguration> configurationState;

    @Mock
    private WritableSingletonState<ClprEndpointManifest> manifestState;

    private V0770ClprSchema subject;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        subject = new V0770ClprSchema();
        configuration = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .withValue("clpr.chainId", "hiero:test")
                .withValue("clpr.protocolVersion", "1")
                .getOrCreateConfig();
    }

    @Test
    void hasVersionMatchingItsName() {
        assertThat(subject.getVersion())
                .isEqualTo(
                        SemanticVersion.newBuilder().major(0).minor(77).patch(0).build());
    }

    @Test
    void leavesInitializationToServiceDuringGenesis() {
        given(migrationContext.isGenesis()).willReturn(true);

        subject.migrate(migrationContext);

        verify(migrationContext, never()).newStates();
        verify(migrationContext, never()).appConfig();
    }

    @Test
    void initializesMissingSingletonsDuringUpgrade() {
        given(migrationContext.isGenesis()).willReturn(false);
        given(migrationContext.newStates()).willReturn(writableStates);
        given(migrationContext.appConfig()).willReturn(configuration);
        given(writableStates.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID))
                .willReturn(configurationState);
        given(writableStates.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                .willReturn(manifestState);

        subject.migrate(migrationContext);

        verify(configurationState).put(org.mockito.ArgumentMatchers.any(ClprLedgerConfiguration.class));
        verify(manifestState).put(org.mockito.ArgumentMatchers.any(ClprEndpointManifest.class));
    }

    @Test
    void doesNotOverwriteExistingSingletonsDuringUpgrade() {
        given(migrationContext.isGenesis()).willReturn(false);
        given(migrationContext.newStates()).willReturn(writableStates);
        given(migrationContext.appConfig()).willReturn(configuration);
        given(writableStates.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID))
                .willReturn(configurationState);
        given(writableStates.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                .willReturn(manifestState);
        given(configurationState.get()).willReturn(ClprLedgerConfiguration.DEFAULT);
        given(manifestState.get()).willReturn(ClprEndpointManifest.DEFAULT);

        subject.migrate(migrationContext);

        verify(configurationState, never()).put(org.mockito.ArgumentMatchers.any());
        verify(manifestState, never()).put(org.mockito.ArgumentMatchers.any());
    }
}
