// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.cryptography.tss.TSS;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ensures committed warm-start keys and proofs remain usable with the pinned native library. */
class CommittedTssFixturesTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ledgerA",
                "ledgerB",
                "ledgerA_mtls",
                "ledgerB_mtls",
                "ledgerA_manifest",
                "ledgerB_manifest",
                "ledgerA_restart",
                "ledgerB_restart"
            })
    void everyNodeCanSignAndTheAggregateHasAValidChainOfTrust(final String name) throws Exception {
        final var destination = tempDir.resolve("genesis-network.json");
        TssFixtures.installFixture(TssFixtures.resolveCachedFixturePath(name), destination);
        final var network = DiskStartupNetworks.loadNetworkFrom(destination).orElseThrow();
        final var metadata = network.tssMetadataOrThrow();
        final var scheme = metadata.activeHintsConstructionOrThrow().hintsSchemeOrThrow();
        final var keys = scheme.preprocessedKeysOrThrow();
        final var library = new HintsLibraryImpl();
        final var message = Bytes.wrap("published native library fixture compatibility");
        final var partials = new HashMap<Integer, Bytes>();
        for (final var party : scheme.nodePartyIds()) {
            final var privateKey = network.nodeTssMetadata().stream()
                    .filter(entry -> entry.nodeId() == party.nodeId())
                    .findFirst()
                    .orElseThrow()
                    .blsPrivateKey();
            assertTrue(privateKey.length() > 0, "Every node needs its own key");
            final var signature = library.signBls(message, privateKey);
            assertTrue(library.verifyBls(
                    metadata.crsStateOrThrow().crs(), signature, message, keys.aggregationKey(), party.partyId()));
            partials.put(party.partyId(), signature);
        }
        assertEquals(network.nodeMetadata().size(), partials.size());
        final var aggregate = library.aggregateSignatures(
                metadata.crsStateOrThrow().crs(), keys.aggregationKey(), keys.verificationKey(), partials);
        assertNotNull(aggregate);
        assertTrue(library.verifyAggregate(aggregate, message, keys.verificationKey(), 1, 3));
        final var wrapsProof = metadata.activeProofConstructionOrThrow()
                .targetProofOrThrow()
                .chainOfTrustProofOrThrow()
                .wrapsProofOrThrow();
        final var signature = ByteBuffer.allocate(
                        (int) (keys.verificationKey().length() + aggregate.length() + wrapsProof.length()))
                .put(keys.verificationKey().toByteArray())
                .put(aggregate.toByteArray())
                .put(wrapsProof.toByteArray())
                .array();
        assertTrue(TSS.verifyTSS(network.ledgerId().toByteArray(), signature, message.toByteArray()));
    }
}
