// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.tss.TssKeyFiles;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
    @SuppressWarnings("unchecked")
    void restoresBoundCapacityAndEpochFromStartupJson() throws Exception {
        final var crs = CRSState.newBuilder()
                .ceremonyId(7L)
                .numParties(16)
                .lastUsedCeremonyId(9L)
                .crs(Bytes.wrap(new byte[304 + 288 * 16]))
                .stage(CRSStage.COMPLETED)
                .build();
        final var construction = HintsConstruction.newBuilder()
                .constructionId(11L)
                .crsId(7L)
                .numParties(16)
                .hintsScheme(new HintsScheme(
                        new PreprocessedKeys(Bytes.wrap("ak"), Bytes.wrap("vk")), List.of(new NodePartyId(0L, 3, 1L))))
                .build();
        final var network = Network.newBuilder()
                .tssMetadata(TssMetadata.newBuilder().crsState(crs).activeHintsConstruction(construction))
                .nodeTssMetadata(NodeTssMetadata.newBuilder()
                        .nodeId(0L)
                        .partyId(3)
                        .hintsKey(Bytes.wrap("hint"))
                        .build())
                .build();
        final var decoded = Network.JSON.parse(new ReadableStreamingData(
                new ByteArrayInputStream(Network.JSON.toJSON(network).getBytes(StandardCharsets.UTF_8))));
        final WritableStates states = mock(WritableStates.class);
        final WritableSingletonState<HintsConstruction> active = mock(WritableSingletonState.class);
        final WritableSingletonState<HintsConstruction> next = mock(WritableSingletonState.class);
        final WritableSingletonState<CRSState> activeCrs = mock(WritableSingletonState.class);
        final WritableSingletonState<CRSState> nextCrs = mock(WritableSingletonState.class);
        final WritableKVState<HintsPartyId, HintsKeySet> keys = mock(WritableKVState.class);
        when(states.<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .thenReturn(active);
        when(states.<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .thenReturn(next);
        when(states.<CRSState>getSingleton(CRS_STATE_STATE_ID)).thenReturn(activeCrs);
        when(states.<CRSState>getSingleton(NEXT_CRS_STATE_ID)).thenReturn(nextCrs);
        when(states.<HintsPartyId, HintsKeySet>get(HINTS_KEY_SETS_STATE_ID)).thenReturn(keys);
        assertThat(TssStartupNetworks.initializeHintsState(states, decoded)).isEqualTo(construction);
        verify(active).put(construction);
        verify(activeCrs).put(crs);
        verify(nextCrs).put(CRSState.DEFAULT);
        verify(keys).put(eq(new HintsPartyId(3, 16, 7L)), argThat(k -> k.key().equals(Bytes.wrap("hint"))));
    }

    @Test
    void refusesToImportUnfinishedOrMismatchedCrs() {
        final var construction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .crsId(2L)
                .numParties(8)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var crs = CRSState.newBuilder()
                .ceremonyId(2L)
                .numParties(8)
                .crs(Bytes.wrap(new byte[304 + 288 * 8]))
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .build();
        final var unfinished = Network.newBuilder()
                .tssMetadata(TssMetadata.newBuilder()
                        .activeHintsConstruction(construction)
                        .crsState(crs))
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(mock(WritableStates.class), unfinished));
        final var mismatched = unfinished
                .copyBuilder()
                .tssMetadata(TssMetadata.newBuilder()
                        .activeHintsConstruction(construction)
                        .crsState(crs.copyBuilder().ceremonyId(3L).stage(CRSStage.COMPLETED)))
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(mock(WritableStates.class), mismatched));
    }

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

    @Test
    void stripsExistingPrivateKeysUnderProdProfile() {
        final Map<Long, NodeTssMetadata> metadata = new HashMap<>();

        TssStartupNetworks.copyExistingNodeMetadata(config("PROD"), networkWithSelfPrivateKeys(), metadata);

        final var self = metadata.get(SELF_NODE_ID);
        assertThat(self.blsPrivateKey()).isEqualTo(Bytes.EMPTY);
        assertThat(self.schnorrPrivateKey()).isEqualTo(Bytes.EMPTY);
        assertThat(self.schnorrPublicKey()).isEqualTo(SCHNORR_KEY_PAIR.publicKey());
    }

    @Test
    void keepsExistingPrivateKeysUnderNonProdProfile() {
        final Map<Long, NodeTssMetadata> metadata = new HashMap<>();

        TssStartupNetworks.copyExistingNodeMetadata(config("DEV"), networkWithSelfPrivateKeys(), metadata);

        final var self = metadata.get(SELF_NODE_ID);
        assertThat(self.blsPrivateKey()).isEqualTo(BLS_PRIVATE_KEY);
        assertThat(self.schnorrPrivateKey()).isEqualTo(SCHNORR_KEY_PAIR.privateKey());
    }

    @Test
    void copiesNothingWhenNoExistingNetwork() {
        final Map<Long, NodeTssMetadata> metadata = new HashMap<>();

        TssStartupNetworks.copyExistingNodeMetadata(config("PROD"), null, metadata);

        assertThat(metadata).isEmpty();
    }

    @Test
    void writesStartupNetworkPrivateKeysUnderNonProdProfile() {
        final var config = config("DEV");

        TssStartupNetworks.writePrivateKeys(networkWithSelfPrivateKeys(), config, SELF_NODE_ID);

        assertThat(TssKeyFiles.readBlsPrivateKey(config, CONSTRUCTION_ID)).contains(BLS_PRIVATE_KEY);
        assertThat(TssKeyFiles.readSchnorrKeyPair(config, CONSTRUCTION_ID)).contains(SCHNORR_KEY_PAIR);
    }

    @Test
    void doesNotWriteStartupNetworkPrivateKeysUnderProdProfile() {
        final var config = config("PROD");

        TssStartupNetworks.writePrivateKeys(networkWithSelfPrivateKeys(), config, SELF_NODE_ID);

        assertThat(TssKeyFiles.readBlsPrivateKey(config, CONSTRUCTION_ID)).isEmpty();
        assertThat(TssKeyFiles.readSchnorrKeyPair(config, CONSTRUCTION_ID)).isEmpty();
    }

    private Network networkWithSelfPrivateKeys() {
        return Network.newBuilder()
                .nodeTssMetadata(NodeTssMetadata.newBuilder()
                        .nodeId(SELF_NODE_ID)
                        .blsPrivateKey(BLS_PRIVATE_KEY)
                        .schnorrPrivateKey(SCHNORR_KEY_PAIR.privateKey())
                        .schnorrPublicKey(SCHNORR_KEY_PAIR.publicKey())
                        .build())
                .build();
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
