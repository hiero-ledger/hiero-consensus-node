// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.ClprServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprServiceTest {
    private final ClprService subject = new ClprService() {
        @Override
        public void registerSchemas(@NonNull SchemaRegistry registry) {}
    };

    @Test
    @DisplayName("Service name is ClprService")
    void verifyServiceName() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo("ClprService");
    }

    @Test
    @DisplayName("RPC definitions match ClprServiceDefinition")
    void verifyRpcDefs() {
        Assertions.assertThat(subject.rpcDefinitions()).containsExactlyInAnyOrder(ClprServiceDefinition.INSTANCE);
    }

    @Test
    @DisplayName("Builder orders endpoints deterministically and honors publicize toggle")
    void buildLedgerConfigurationOrdersEndpointsAndHonorsPublicizeToggle() {
        final var roster = Roster.newBuilder()
                .rosterEntries(List.of(rosterEntry(3L, "c3"), rosterEntry(1L, "c1"), rosterEntry(2L, "c2")))
                .build();
        final var endpointsById = java.util.Map.of(
                1L, serviceEndpoint(80),
                2L, serviceEndpoint(81),
                3L, serviceEndpoint(82));
        final var accountIdsById = java.util.Map.of(
                1L, AccountID.newBuilder().accountNum(1001L).build(),
                2L, AccountID.newBuilder().accountNum(1002L).build(),
                3L, AccountID.newBuilder().accountNum(1003L).build());
        final var ledgerId = Bytes.wrap("ledger-A");
        final var payer = AccountID.newBuilder().accountNum(3L).build();
        final var consensus = Instant.ofEpochSecond(1_234_567L, 890);

        final var txnWithEndpoints = ClprService.buildLedgerConfigurationUpdateTransactionBody(
                roster, payer, ledgerId, consensus, true, endpointsById::get, accountIdsById::get);
        final var configWithEndpoints = extractConfig(txnWithEndpoints);
        assertThat(configWithEndpoints.endpoints())
                .extracting(ep -> ep.endpoint().port())
                .containsExactly(80, 81, 82);
        assertThat(configWithEndpoints.endpoints())
                .extracting(ClprEndpoint::signingCertificate)
                .containsExactly(Bytes.wrap("c1"), Bytes.wrap("c2"), Bytes.wrap("c3"));
        assertThat(configWithEndpoints.endpoints())
                .extracting(ep -> ep.nodeAccountIdOrElse(AccountID.DEFAULT).accountNum())
                .containsExactly(1001L, 1002L, 1003L);

        final var txnWithoutEndpoints = ClprService.buildLedgerConfigurationUpdateTransactionBody(
                roster, payer, ledgerId, consensus, false, endpointsById::get, accountIdsById::get);
        final var configWithoutEndpoints = extractConfig(txnWithoutEndpoints);
        assertThat(configWithoutEndpoints.endpoints())
                .extracting(ep -> ep.endpoint() == null ? null : ep.endpoint().port())
                .containsExactly(null, null, null);
        assertThat(configWithoutEndpoints.endpoints())
                .extracting(ClprEndpoint::signingCertificate)
                .containsExactly(Bytes.wrap("c1"), Bytes.wrap("c2"), Bytes.wrap("c3"));
        assertThat(configWithoutEndpoints.endpoints())
                .extracting(ep -> ep.nodeAccountIdOrElse(AccountID.DEFAULT).accountNum())
                .containsExactly(1001L, 1002L, 1003L);
    }

    private static ClprLedgerConfiguration extractConfig(final com.hedera.hapi.node.transaction.TransactionBody txn) {
        final StateProof proof = txn.clprSetLedgerConfigurationOrThrow().ledgerConfigurationProofOrThrow();
        try {
            final var stateItem =
                    StateItem.PROTOBUF.parse(proof.paths().getFirst().stateItemLeafOrThrow());
            final StateValue value = stateItem.valueOrThrow();
            return value.clprServiceIConfigurationsOrThrow();
        } catch (final ParseException e) {
            throw new IllegalStateException("Unable to parse state proof", e);
        }
    }

    private static RosterEntry rosterEntry(final long nodeId, final String certValue) {
        return RosterEntry.newBuilder()
                .nodeId(nodeId)
                .gossipCaCertificate(Bytes.wrap(certValue))
                .gossipEndpoint(List.of(serviceEndpoint(80)))
                .build();
    }

    private static ServiceEndpoint serviceEndpoint(final int port) {
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                .port(port)
                .build();
    }
}
