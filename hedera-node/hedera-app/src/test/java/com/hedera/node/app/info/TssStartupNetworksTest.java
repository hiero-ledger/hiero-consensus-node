// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.tss.TssKeyFiles;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TssStartupNetworksTest {
    private static final long SELF_NODE_ID = 0L;
    private static final long CONSTRUCTION_ID = 0L;
    private static final Bytes BLS_PRIVATE_KEY = Bytes.wrap("bls-private-key");
    private static final TssKeyFiles.SchnorrKeyPair SCHNORR_KEY_PAIR =
            new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("schnorr-private"), Bytes.wrap("schnorr-public"));

    @TempDir
    private Path tempDir;

    @Test
    void embedsSelfPrivateKeysUnderNonProdProfile() {
        final var config = config("DEV");
        givenLocalKeyFiles(config);
        final var metadata = metadataWithSelfNode();

        TssStartupNetworks.addPrivateKeys(config, SELF_NODE_ID, metadata, hintsConstruction(), proofConstruction());

        final var self = metadata.get(SELF_NODE_ID);
        assertThat(self.blsPrivateKey()).isEqualTo(BLS_PRIVATE_KEY);
        assertThat(self.schnorrPrivateKey()).isEqualTo(SCHNORR_KEY_PAIR.privateKey());
        assertThat(self.schnorrPublicKey()).isEqualTo(SCHNORR_KEY_PAIR.publicKey());
    }

    @Test
    void doesNotEmbedSelfPrivateKeysUnderProdProfile() {
        final var config = config("PROD");
        givenLocalKeyFiles(config);
        final var metadata = metadataWithSelfNode();

        TssStartupNetworks.addPrivateKeys(config, SELF_NODE_ID, metadata, hintsConstruction(), proofConstruction());

        final var self = metadata.get(SELF_NODE_ID);
        assertThat(self.blsPrivateKey()).isEqualTo(Bytes.EMPTY);
        assertThat(self.schnorrPrivateKey()).isEqualTo(Bytes.EMPTY);
    }

    private Map<Long, NodeTssMetadata> metadataWithSelfNode() {
        final Map<Long, NodeTssMetadata> metadata = new HashMap<>();
        metadata.put(
                SELF_NODE_ID, NodeTssMetadata.newBuilder().nodeId(SELF_NODE_ID).build());
        return metadata;
    }

    private void givenLocalKeyFiles(final Configuration config) {
        TssKeyFiles.writeBlsPrivateKey(config, CONSTRUCTION_ID, BLS_PRIVATE_KEY);
        TssKeyFiles.writeSchnorrKeyPair(config, CONSTRUCTION_ID, SCHNORR_KEY_PAIR);
    }

    private HintsConstruction hintsConstruction() {
        return HintsConstruction.newBuilder().constructionId(CONSTRUCTION_ID).build();
    }

    private HistoryProofConstruction proofConstruction() {
        return HistoryProofConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .build();
    }

    private Configuration config(final String profile) {
        return HederaTestConfigBuilder.create()
                .withValue("tss.tssKeysPath", tempDir.toAbsolutePath().toString())
                .withValue("hedera.profiles.active", profile)
                .getOrCreateConfig();
    }
}
