// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.MultiNetworkExtension.withRuntimePorts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.Test;

class MultiNetworkExtensionTest {
    private static final Bytes LOCALHOST = Bytes.wrap(new byte[] {127, 0, 0, 1});
    private static final Bytes VERIFICATION_KEY = Bytes.wrap("verification-key");
    private static final Map<Long, com.hedera.services.bdd.junit.hedera.NodeMetadata> RUNTIME_NODES = Map.of(
            0L, runtimeNode(0, 35000),
            1L, runtimeNode(1, 35010));

    @Test
    void transportRewriteRebindsBothConstructionsAndPreservesCryptographicMaterial() {
        final var original = fixture();
        final var originalMetadata = original.tssMetadataOrThrow();

        final var rewritten = withRuntimePorts(original, RUNTIME_NODES);
        final var updatedMetadata = rewritten.tssMetadataOrThrow();
        final var newHash = rosterHash(rewritten);
        assertNotEquals(rosterHash(original), newHash);
        // Both references must match the platform's genesis roster to reuse the completed constructions.
        assertEquals(
                originalMetadata
                        .activeHintsConstructionOrThrow()
                        .copyBuilder()
                        .sourceRosterHash(newHash)
                        .targetRosterHash(newHash)
                        .build(),
                updatedMetadata.activeHintsConstructionOrThrow());
        assertEquals(
                originalMetadata
                        .activeProofConstructionOrThrow()
                        .copyBuilder()
                        .sourceRosterHash(newHash)
                        .targetRosterHash(newHash)
                        .build(),
                updatedMetadata.activeProofConstructionOrThrow());
        assertEquals(originalMetadata.crsState(), updatedMetadata.crsState());
        assertEquals(original.nodeTssMetadata(), rewritten.nodeTssMetadata());
        assertEquals(original.ledgerId(), rewritten.ledgerId());
        for (int i = 0; i < original.nodeMetadata().size(); i++) {
            final var before = original.nodeMetadata().get(i);
            final var after = rewritten.nodeMetadata().get(i);
            final var runtime = RUNTIME_NODES.get(before.rosterEntryOrThrow().nodeId());
            final var gossip = List.of(endpoint(runtime.internalGossipPort()), endpoint(runtime.externalGossipPort()));
            assertEquals(
                    before.rosterEntryOrThrow()
                            .copyBuilder()
                            .gossipEndpoint(gossip)
                            .build(),
                    after.rosterEntry());
            assertEquals(
                    before.nodeOrThrow()
                            .copyBuilder()
                            .gossipEndpoint(gossip)
                            .serviceEndpoint(List.of(endpoint(runtime.grpcPort())))
                            .build(),
                    after.node());
        }
    }

    @Test
    void preservesReferencesToOtherHistoricalRosters() {
        final var original = fixture();
        final var metadata = original.tssMetadataOrThrow();
        final var historicalHash = Bytes.wrap("historical-roster");
        final var historicalHints = metadata.activeHintsConstructionOrThrow()
                .copyBuilder()
                .sourceRosterHash(historicalHash)
                .build();
        final var historicalProof = metadata.activeProofConstructionOrThrow()
                .copyBuilder()
                .sourceRosterHash(historicalHash)
                .build();
        final var input = original.copyBuilder()
                .tssMetadata(metadata.copyBuilder()
                        .activeHintsConstruction(historicalHints)
                        .activeProofConstruction(historicalProof))
                .build();

        final var rewritten = withRuntimePorts(input, RUNTIME_NODES);
        final var newHash = rosterHash(rewritten);

        assertEquals(
                historicalHints.copyBuilder().targetRosterHash(newHash).build(),
                rewritten.tssMetadataOrThrow().activeHintsConstructionOrThrow());
        assertEquals(
                historicalProof.copyBuilder().targetRosterHash(newHash).build(),
                rewritten.tssMetadataOrThrow().activeProofConstructionOrThrow());
    }

    @Test
    void rewritingTheSameRuntimePortsIsIdempotent() {
        final var rewritten = withRuntimePorts(fixture(), RUNTIME_NODES);

        assertEquals(rewritten, withRuntimePorts(rewritten, RUNTIME_NODES));
    }

    @Test
    void doesNotAddTssMetadataWhenAbsent() {
        final var original = Network.newBuilder().nodeMetadata(node(0), node(1)).build();

        final var rewritten = withRuntimePorts(original, RUNTIME_NODES);

        assertNotEquals(rosterHash(original), rosterHash(rewritten));
        assertFalse(rewritten.hasTssMetadata());
    }

    private static Network fixture() {
        final var base = Network.newBuilder()
                .nodeMetadata(node(0), node(1))
                .ledgerId(Bytes.wrap("ledger-id"))
                .nodeTssMetadata(NodeTssMetadata.newBuilder()
                        .nodeId(0)
                        .partyId(0)
                        .hintsKey(Bytes.wrap("hint"))
                        .blsPrivateKey(Bytes.wrap("bls-private-key"))
                        .schnorrPublicKey(Bytes.wrap("schnorr-public-key"))
                        .build())
                .build();
        final var hash = rosterHash(base);
        return base.copyBuilder()
                .tssMetadata(TssMetadata.newBuilder()
                        .crsState(CRSState.newBuilder()
                                .ceremonyId(7)
                                .numParties(4)
                                .stage(CRSStage.COMPLETED)
                                .crs(Bytes.wrap("completed-crs")))
                        .activeHintsConstruction(HintsConstruction.newBuilder()
                                .constructionId(11)
                                .sourceRosterHash(hash)
                                .targetRosterHash(hash)
                                .crsId(7)
                                .numParties(4)
                                .hintsScheme(new HintsScheme(
                                        new PreprocessedKeys(Bytes.wrap("aggregation-key"), VERIFICATION_KEY),
                                        List.of(new NodePartyId(0, 0, 10), new NodePartyId(1, 1, 20)))))
                        .activeProofConstruction(HistoryProofConstruction.newBuilder()
                                .constructionId(12)
                                .sourceRosterHash(hash)
                                .targetRosterHash(hash)
                                .targetProof(HistoryProof.newBuilder()
                                        .targetHistory(History.newBuilder().metadata(VERIFICATION_KEY))
                                        .chainOfTrustProof(
                                                ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("wraps-proof"))))))
                .build();
    }

    private static NodeMetadata node(final long id) {
        final var gossip = List.of(endpoint(30003 + (int) id * 10), endpoint(30004 + (int) id * 10));
        return NodeMetadata.newBuilder()
                .rosterEntry(RosterEntry.newBuilder()
                        .nodeId(id)
                        .weight((id + 1) * 10)
                        .gossipCaCertificate(Bytes.wrap("gossip-certificate-" + id))
                        .gossipEndpoint(gossip))
                .node(Node.newBuilder()
                        .nodeId(id)
                        .accountId(AccountID.newBuilder().accountNum(id + 3))
                        .gossipEndpoint(gossip)
                        .serviceEndpoint(List.of(endpoint(30000 + (int) id * 10))))
                .build();
    }

    private static com.hedera.services.bdd.junit.hedera.NodeMetadata runtimeNode(final long id, final int port) {
        return new com.hedera.services.bdd.junit.hedera.NodeMetadata(
                id,
                "fixture",
                "node" + id,
                AccountID.DEFAULT,
                "localhost",
                port,
                port + 1,
                port + 3,
                port + 4,
                port + 5,
                null);
    }

    private static ServiceEndpoint endpoint(final int port) {
        return ServiceEndpoint.newBuilder().ipAddressV4(LOCALHOST).port(port).build();
    }

    private static Bytes rosterHash(final Network network) {
        return RosterUtils.hash(RosterUtils.rosterFrom(network)).getBytes();
    }
}
