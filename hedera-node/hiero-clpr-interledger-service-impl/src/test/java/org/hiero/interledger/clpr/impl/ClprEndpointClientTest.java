// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(new ClprConfig(true, 5000, true, true));
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
        when(selfNodeInfo.zeroWeight()).thenReturn(false);
        // Single-node roster: ensures round-robin always assigns all ledgers to this node
        when(networkInfo.addressBook()).thenReturn(List.of(selfNodeInfo));
        when(stateProofManager.getLatestConsensusRound()).thenReturn(0L);
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
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(new ClprConfig(false, 5000, true, true));

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

    @Test
    void runOnceSkipsLedgerWhenNotAssigned() throws Exception {
        // Set up a 3-node roster where this node (nodeId=1) is at index 1
        final var node0 = org.mockito.Mockito.mock(NodeInfo.class);
        when(node0.nodeId()).thenReturn(0L);
        when(node0.zeroWeight()).thenReturn(false);
        final var node2 = org.mockito.Mockito.mock(NodeInfo.class);
        when(node2.nodeId()).thenReturn(2L);
        when(node2.zeroWeight()).thenReturn(false);
        when(networkInfo.addressBook()).thenReturn(List.of(node0, selfNodeInfo, node2));

        // Choose a consensus round such that the remote ledger is NOT assigned to this node.
        // selfIndex = 1, N = 3, roundsPerRotation = max(1, 5000/1000) = 5
        // We need: (ledgerHash + cycle) mod 3 != 1
        // Brute-force: try round=0 -> cycle=0 -> assignedIndex = ledgerHash mod 3
        // If ledgerHash mod 3 != 1, round=0 works; otherwise try round=5 -> cycle=1
        final int ledgerHash = Math.floorMod(remoteClprLedgerId.ledgerId().hashCode(), 3);
        // Pick a round where (ledgerHash + cycle) mod 3 != 1
        long testRound = 0;
        for (int c = 0; c < 3; c++) {
            if (Math.floorMod(ledgerHash + c, 3) != 1) {
                testRound = (long) c * 5; // cycle = testRound / 5 = c
                break;
            }
        }
        when(stateProofManager.getLatestConsensusRound()).thenReturn(testRound);

        final var localConfig = localClprConfig;
        final var remoteStored = remoteClprConfig
                .copyBuilder()
                .timestamp(Timestamp.newBuilder().seconds(10).nanos(0).build())
                .endpoints(List.of(org.hiero.hapi.interledger.state.clpr.ClprEndpoint.newBuilder()
                        .endpoint(remoteEndpoint)
                        .signingCertificate(Bytes.wrap("cert"))
                        .build()))
                .build();
        when(stateProofManager.getLocalLedgerId()).thenReturn(localClprLedgerId);
        final var localProof = buildLocalClprStateProofWrapper(localConfig);
        when(stateProofManager.getLedgerConfiguration(localClprLedgerId)).thenReturn(localProof);
        when(stateProofManager.readAllLedgerConfigurations())
                .thenReturn(java.util.Map.of(localClprLedgerId, localConfig, remoteClprLedgerId, remoteStored));

        subject.runOnce();

        // This node should NOT have contacted the remote ledger
        verifyNoInteractions(connectionManager);
    }

    @Test
    void runOnceContactsLedgerWhenAssigned() throws Exception {
        // Set up a 3-node roster where this node (nodeId=1) is at index 1
        final var node0 = org.mockito.Mockito.mock(NodeInfo.class);
        when(node0.nodeId()).thenReturn(0L);
        when(node0.zeroWeight()).thenReturn(false);
        final var node2 = org.mockito.Mockito.mock(NodeInfo.class);
        when(node2.nodeId()).thenReturn(2L);
        when(node2.zeroWeight()).thenReturn(false);
        when(networkInfo.addressBook()).thenReturn(List.of(node0, selfNodeInfo, node2));

        // Choose a consensus round such that the remote ledger IS assigned to this node.
        // selfIndex = 1, N = 3, roundsPerRotation = 5
        // We need: (ledgerHash + cycle) mod 3 == 1
        final int ledgerHash = Math.floorMod(remoteClprLedgerId.ledgerId().hashCode(), 3);
        long testRound = 0;
        for (int c = 0; c < 3; c++) {
            if (Math.floorMod(ledgerHash + c, 3) == 1) {
                testRound = (long) c * 5;
                break;
            }
        }
        when(stateProofManager.getLatestConsensusRound()).thenReturn(testRound);

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

        // This node SHOULD have contacted the remote ledger
        verify(remoteClient).setConfiguration(payerAccountId, remoteNodeAccountId, localProof);
    }

    @Test
    void isAssignedToLedgerRotatesAcrossAllNodes() {
        // With N=3, over 3 consecutive cycles, each node index should be assigned exactly once
        final int rosterSize = 3;
        boolean[] assigned = new boolean[rosterSize];
        for (int cycle = 0; cycle < rosterSize; cycle++) {
            for (int nodeIndex = 0; nodeIndex < rosterSize; nodeIndex++) {
                if (subject.isAssignedToLedger(remoteClprLedgerId, cycle, nodeIndex, rosterSize)) {
                    assertFalse(assigned[cycle], "Cycle " + cycle + " should assign to exactly one node");
                    assigned[cycle] = true;
                }
            }
        }
        // Every cycle should have exactly one assigned node
        for (int cycle = 0; cycle < rosterSize; cycle++) {
            assertTrue(assigned[cycle], "Cycle " + cycle + " should have an assigned node");
        }
    }

    @Test
    void isAssignedToLedgerFallsBackWhenRosterEmpty() {
        // When roster size is 0 or selfIndex is -1, all ledgers should be assigned (graceful degradation)
        assertTrue(subject.isAssignedToLedger(remoteClprLedgerId, 0, -1, 3));
        assertTrue(subject.isAssignedToLedger(remoteClprLedgerId, 0, 0, 0));
    }
}
