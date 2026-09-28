// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.ClprServiceImpl;
import com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprServiceImplTest {

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ClprLedgerConfiguration> configState;

    @Mock
    private WritableSingletonState<ClprEndpointManifest> manifestState;

    private ClprServiceImpl subject;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        subject = new ClprServiceImpl();
        given(writableStates.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID))
                .willReturn(configState);
        given(writableStates.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                .willReturn(manifestState);
        configuration = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .withValue("clpr.chainId", "hiero:test")
                .withValue("clpr.protocolVersion", "1")
                .getOrCreateConfig();
    }

    @Test
    @DisplayName("seeded ClprLedgerConfiguration has non-empty service_address of 20 bytes")
    void seedsServiceAddress() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configState).put(captor.capture());
        final var seeded = captor.getValue();

        assertThat(seeded.serviceAddress()).isNotNull();
        assertThat(seeded.serviceAddress().length()).isEqualTo(20);
    }

    @Test
    @DisplayName("seeded ClprLedgerConfiguration has timestamp with seconds > 0")
    void seedsNonZeroTimestamp() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configState).put(captor.capture());
        final var seeded = captor.getValue();

        assertThat(seeded.timestamp()).isNotNull();
        assertThat(seeded.timestamp().seconds()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("seeded ClprLedgerConfiguration has max_gas_per_message > 0")
    void seedsPositiveMaxGasPerMessage() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configState).put(captor.capture());
        final var seeded = captor.getValue();

        assertThat(seeded.throttles()).isNotNull();
        assertThat(seeded.throttles().maxGasPerMessage()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("seeded ClprLedgerConfiguration service_address is 0x16e zero-padded")
    void seedsCorrectServiceAddress() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(configState).put(captor.capture());
        final var seeded = captor.getValue();

        assertThat(seeded.serviceAddress()).isEqualTo(V0770ClprSchema.CLPR_SERVICE_ADDRESS);
    }

    @Test
    @DisplayName("seeded ClprEndpointManifest has version 1")
    void seedsManifestVersionOne() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprEndpointManifest.class);
        verify(manifestState).put(captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("seeded ClprEndpointManifest has empty endpoints list")
    void seedsManifestWithEmptyEndpoints() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprEndpointManifest.class);
        verify(manifestState).put(captor.capture());
        assertThat(captor.getValue().endpoints()).isEmpty();
    }

    @Test
    @DisplayName("seeded ClprEndpointManifest has CLPR service address (20 bytes, 0x16e)")
    void seedsManifestServiceAddress() {
        subject.doGenesisSetup(writableStates, configuration);

        final var captor = ArgumentCaptor.forClass(ClprEndpointManifest.class);
        verify(manifestState).put(captor.capture());
        final var seeded = captor.getValue();
        assertThat(seeded.serviceAddress()).isEqualTo(CLPR_EVM_ADDRESS_BYTES);
        assertThat(seeded.serviceAddress().length()).isEqualTo(20);
    }
}
