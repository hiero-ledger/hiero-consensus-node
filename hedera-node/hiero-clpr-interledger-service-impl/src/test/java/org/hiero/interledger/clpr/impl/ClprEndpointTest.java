// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.interledger.clpr.client.ClprClient;
import org.hiero.interledger.clpr.impl.client.ClprConnectionManager;
import org.hiero.interledger.clpr.impl.test.ClprTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClprEndpointTest extends ClprTestBase {

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

    private ClprEndpoint subject;

    private ServiceEndpoint localEndpoint;
    private ServiceEndpoint remoteEndpoint;
    private AccountID selfAccountId;
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
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(new ClprConfig(5000, true, true));
        when(configuration.getConfigData(GrpcConfig.class))
                .thenReturn(new GrpcConfig(50211, 50212, true, 50213, 60211, 60212, 4194304, 4194304, 4194304));

        when(networkInfo.selfNodeInfo()).thenReturn(selfNodeInfo);
        when(selfNodeInfo.nodeId()).thenReturn(1L);
        selfAccountId = AccountID.newBuilder().accountNum(3).build();
        when(selfNodeInfo.accountId()).thenReturn(selfAccountId);

        when(remoteNodeInfo.nodeId()).thenReturn(2L);
        when(remoteNodeInfo.hapiEndpoints()).thenReturn(List.of(remoteEndpoint));
        remoteAccountId = AccountID.newBuilder().accountNum(4).build();
        when(remoteNodeInfo.accountId()).thenReturn(remoteAccountId);
        when(networkInfo.addressBook()).thenReturn(List.of(selfNodeInfo, remoteNodeInfo));

        subject = new ClprEndpoint(
                networkInfo,
                configProvider,
                connectionManager,
                Clock.fixed(Instant.ofEpochSecond(1_000, 100), ZoneOffset.UTC));
    }

    @Test
    void runOnceSkipsWhenDevModeDisabled() {
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(new ClprConfig(5000, true, false));

        subject.runOnce();

        verifyNoInteractions(connectionManager);
    }

    @Test
    void runOnceSkipsWhenLocalConfigMissing() throws UnknownHostException {
        when(connectionManager.createClient(localEndpoint)).thenReturn(localClient);
        when(localClient.getConfiguration()).thenReturn(null);
        when(localClient.setConfiguration(any(), any(), any())).thenReturn(ResponseCodeEnum.SUCCESS);

        subject.runOnce();

        verify(localClient).getConfiguration();
        verify(localClient).setConfiguration(eq(selfAccountId), eq(selfAccountId), any());
        verify(remoteClient, never()).setConfiguration(any(), any(), any());
    }

    @Test
    void runOnceRefreshesExistingConfiguration() throws UnknownHostException {
        final var localConfig = localClprConfig
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(50).nanos(0).build())
                .build();

        when(connectionManager.createClient(localEndpoint)).thenReturn(localClient);
        when(localClient.getConfiguration()).thenReturn(localConfig);
        when(localClient.setConfiguration(any(), any(), any())).thenReturn(ResponseCodeEnum.SUCCESS);

        subject.runOnce();

        final ArgumentCaptor<ClprLedgerConfiguration> captor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(localClient).setConfiguration(eq(selfAccountId), eq(selfAccountId), captor.capture());
        final var submittedTimestamp = captor.getValue().timestampOrThrow();
        final var existingTimestamp = localConfig.timestampOrThrow();
        assertTrue(submittedTimestamp.seconds() > existingTimestamp.seconds()
                || (submittedTimestamp.seconds() == existingTimestamp.seconds()
                        && submittedTimestamp.nanos() > existingTimestamp.nanos()));
        verifyNoInteractions(remoteClient);
    }
}
