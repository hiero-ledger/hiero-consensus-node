// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hiero.interledger.clpr.client.ClprClient;
import org.hiero.interledger.clpr.impl.client.ClprConnectionManager;
import org.hiero.interledger.clpr.impl.test.ClprTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClprEndpointClientTest extends ClprTestBase {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration configuration;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private NodeInfo remoteNodeInfo;

    @Mock
    private ClprConnectionManager connectionManager;

    @Mock
    private ClprClient localClient;

    @Mock
    private ClprClient remoteClient;

    @Mock
    private ClprStateProofManager stateProofManager;

    private ClprEndpointClient subject;

    private ServiceEndpoint localEndpoint;
    private ServiceEndpoint remoteEndpoint;
    private AccountID selfAccountId;
    private AccountID payerAccountId;
    private AccountID remoteAccountId;

    @BeforeEach
    void setUp() {
        setupBase();
        localEndpoint = ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(50211)
                .build();
        remoteEndpoint = ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(new byte[] {127, 0, 0, 2}))
                .port(50212)
                .build();

        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(new ClprConfig(true, 5000, true, 5, 6144));
        when(configuration.getConfigData(GrpcConfig.class))
                .thenReturn(new GrpcConfig(50211, 50212, true, 50213, 60211, 60212, 4194304, 4194304, 4194304));
        final var hederaConfig = org.mockito.Mockito.mock(HederaConfig.class);
        when(hederaConfig.shard()).thenReturn(0L);
        when(hederaConfig.realm()).thenReturn(0L);
        when(configuration.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        final var accountsConfig = org.mockito.Mockito.mock(AccountsConfig.class);
        when(accountsConfig.treasury()).thenReturn(2L);
        when(configuration.getConfigData(AccountsConfig.class)).thenReturn(accountsConfig);

        when(networkInfo.selfNodeInfo()).thenReturn(selfNodeInfo);
        when(selfNodeInfo.nodeId()).thenReturn(1L);
        selfAccountId = AccountID.newBuilder().accountNum(3).build();
        payerAccountId = AccountID.newBuilder().accountNum(2).build();
        when(selfNodeInfo.accountId()).thenReturn(selfAccountId);

        subject = new ClprEndpointClient(
                networkInfo,
                configProvider,
                connectionManager,
                stateProofManager,
                Clock.fixed(Instant.ofEpochSecond(1_000, 100), ZoneOffset.UTC));
    }

    @Test
    void runOnceSkipsWhenClprDisabled() {
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(new ClprConfig(false, 5000, true, 5, 6144));

        subject.runOnce();

        verifyNoInteractions(connectionManager);
    }

    @Test
    void runOnceSkipsWhenLocalConfigMissing() {
        when(stateProofManager.getLocalLedgerId()).thenReturn(null);

        subject.runOnce();

        verifyNoInteractions(connectionManager);
    }

    @Test
    void runOncePublishesAndPullsRemoteConfig() throws Exception {
        final var localConfig = localClprConfig;
        final var remoteNodeAccountId = AccountID.newBuilder().accountNum(7).build();
        final var remoteStored = remoteClprConfig
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(10).nanos(0).build())
                .endpoints(List.of(org.hiero.hapi.interledger.state.clpr.ClprEndpoint.newBuilder()
                        .endpoint(remoteEndpoint)
                        .signingCertificate(Bytes.wrap("cert"))
                        .nodeAccountId(remoteNodeAccountId)
                        .build()))
                .build();
        final var remoteUpdated = remoteStored
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(20).nanos(0).build())
                .build();
        when(stateProofManager.getLocalLedgerId()).thenReturn(localClprLedgerId);
        final var localProof = buildLocalClprStateProofWrapper(localConfig);
        when(stateProofManager.getLedgerConfiguration(localClprLedgerId)).thenReturn(localProof);
        when(stateProofManager.readAllLedgerConfigurations())
                .thenReturn(java.util.Map.of(localClprLedgerId, localConfig, remoteClprLedgerId, remoteStored));
        when(connectionManager.createClient(remoteEndpoint)).thenReturn(remoteClient);
        when(connectionManager.createClient(localEndpoint)).thenReturn(localClient);
        when(remoteClient.setConfiguration(payerAccountId, remoteNodeAccountId, localProof))
                .thenReturn(ResponseCodeEnum.OK);
        final var remoteProof = buildLocalClprStateProofWrapper(remoteUpdated);
        when(remoteClient.getConfiguration(remoteClprLedgerId)).thenReturn(remoteProof);

        subject.runOnce();

        verify(remoteClient).setConfiguration(payerAccountId, remoteNodeAccountId, localProof);
        verify(remoteClient).getConfiguration(remoteClprLedgerId);
        verify(localClient).setConfiguration(payerAccountId, remoteNodeAccountId, remoteProof);
    }

    @Test
    void runOncePullsRemoteConfigEvenWhenPublishFails() throws Exception {
        final var localConfig = localClprConfig;
        final var remoteNodeAccountId = AccountID.newBuilder().accountNum(7).build();
        final var remoteStored = remoteClprConfig
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(10).nanos(0).build())
                .endpoints(List.of(org.hiero.hapi.interledger.state.clpr.ClprEndpoint.newBuilder()
                        .endpoint(remoteEndpoint)
                        .signingCertificate(Bytes.wrap("cert"))
                        .nodeAccountId(remoteNodeAccountId)
                        .build()))
                .build();
        final var remoteUpdated = remoteStored
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(20).nanos(0).build())
                .build();
        when(stateProofManager.getLocalLedgerId()).thenReturn(localClprLedgerId);
        final var localProof = buildLocalClprStateProofWrapper(localConfig);
        when(stateProofManager.getLedgerConfiguration(localClprLedgerId)).thenReturn(localProof);
        when(stateProofManager.readAllLedgerConfigurations())
                .thenReturn(java.util.Map.of(localClprLedgerId, localConfig, remoteClprLedgerId, remoteStored));
        when(connectionManager.createClient(remoteEndpoint)).thenReturn(remoteClient);
        when(connectionManager.createClient(localEndpoint)).thenReturn(localClient);
        when(remoteClient.setConfiguration(payerAccountId, remoteNodeAccountId, localProof))
                .thenReturn(ResponseCodeEnum.INVALID_TRANSACTION);
        final var remoteProof = buildLocalClprStateProofWrapper(remoteUpdated);
        when(remoteClient.getConfiguration(remoteClprLedgerId)).thenReturn(remoteProof);

        subject.runOnce();

        verify(remoteClient).setConfiguration(payerAccountId, remoteNodeAccountId, localProof);
        verify(remoteClient).getConfiguration(remoteClprLedgerId);
        verify(localClient).setConfiguration(payerAccountId, remoteNodeAccountId, remoteProof);
    }
}
