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
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.tss.TssKeyFiles;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TssStartupNetworksTest {
    private static final long SELF_NODE_ID = 0L;
    private static final long CONSTRUCTION_ID = 0L;
    private static final Bytes BLS_PRIVATE_KEY = Bytes.wrap("bls-private-key");
    private static final TssKeyFiles.SchnorrKeyPair SCHNORR_KEY_PAIR =
            new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("schnorr-private"), Bytes.wrap("schnorr-public"));

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotInitializeHintsStateUnderProdProfile(final boolean globalMetadata) {
        final var states = mock(WritableStates.class);
        final var network = globalMetadata
                ? Network.newBuilder()
                        .tssMetadata(TssMetadata.newBuilder().activeHintsConstruction(hintsConstruction()))
                        .build()
                : networkWithSelfPrivateKeys();

        assertThat(TssStartupNetworks.initializeHintsState(states, network, config("PROD")))
                .isEqualTo(HintsConstruction.DEFAULT);

        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotInitializeHistoryStateUnderProdProfile(final boolean globalMetadata) {
        final var states = mock(WritableStates.class);
        final var network = globalMetadata
                ? Network.newBuilder()
                        .tssMetadata(TssMetadata.newBuilder().activeProofConstruction(proofConstruction()))
                        .build()
                : networkWithSelfPrivateKeys();

        assertThat(TssStartupNetworks.initializeHistoryState(states, network, config("PROD")))
                .isEqualTo(HistoryProofConstruction.DEFAULT);

        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEV", "TEST", "PROD"})
    void doesNotInitializeStateWithoutTssMetadata(final String profile) {
        final var states = mock(WritableStates.class);
        final var config = config(profile);

        assertThat(TssStartupNetworks.initializeHintsState(states, Network.DEFAULT, config))
                .isEqualTo(HintsConstruction.DEFAULT);
        assertThat(TssStartupNetworks.initializeHistoryState(states, Network.DEFAULT, config))
                .isEqualTo(HistoryProofConstruction.DEFAULT);

        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 64})
    @SuppressWarnings("unchecked")
    void restoresBoundCapacityAndEpochFromStartupJson(final int capacity) throws Exception {
        final var crs = CRSState.newBuilder()
                .ceremonyId(7L)
                .numParties(capacity)
                .lastUsedCeremonyId(9L)
                .crs(Bytes.wrap(new byte[304 + 288 * capacity]))
                .stage(CRSStage.COMPLETED)
                .build();
        final var construction = HintsConstruction.newBuilder()
                .constructionId(11L)
                .crsId(7L)
                .numParties(capacity)
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
        assertThat(TssStartupNetworks.initializeHintsState(states, decoded, config("DEV")))
                .isEqualTo(construction);
        verify(active).put(construction);
        verify(activeCrs).put(crs);
        verify(nextCrs).put(CRSState.DEFAULT);
        verify(keys)
                .put(eq(new HintsPartyId(3, capacity, 7L)), argThat(k -> k.key().equals(Bytes.wrap("hint"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 512})
    void refusesOversizedStartupCrsBeforeWritingState(final int capacity) {
        final var states = mock(WritableStates.class);
        final var network = startupNetwork(capacity, 1, 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(states, network, config("DEV")));

        verifyNoInteractions(states);
    }

    @Test
    void refusesReservedPartyInStartupSchemeBeforeWritingState() {
        final var states = mock(WritableStates.class);
        final var network = startupNetwork(64, 63, 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(states, network, config("DEV")));

        verifyNoInteractions(states);
    }

    @Test
    void refusesReservedPartyInStartupHintBeforeWritingState() {
        final var states = mock(WritableStates.class);
        final var network = startupNetwork(64, 1, 63);

        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(states, network, config("DEV")));

        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @CsvSource({"64, 63", "32, 31"})
    void refusesStartupRosterBeyondSupportedBoundCapacity(final int capacity, final int nodeCount) {
        final var states = mock(WritableStates.class);
        final var network = startupNetwork(capacity, 1, 1)
                .copyBuilder()
                .nodeMetadata(IntStream.range(0, nodeCount)
                        .mapToObj(i -> NodeMetadata.newBuilder()
                                .rosterEntry(RosterEntry.newBuilder().nodeId(i).weight(1))
                                .build())
                        .toList())
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(states, network, config("DEV")));

        verifyNoInteractions(states);
    }

    private static Network startupNetwork(final int capacity, final int schemeParty, final int hintParty) {
        final var crs = CRSState.newBuilder()
                .ceremonyId(7L)
                .numParties(capacity)
                .crs(Bytes.wrap(new byte[304 + 288 * capacity]))
                .stage(CRSStage.COMPLETED)
                .build();
        final var construction = HintsConstruction.newBuilder()
                .constructionId(11L)
                .crsId(7L)
                .numParties(capacity)
                .hintsScheme(new HintsScheme(
                        new PreprocessedKeys(Bytes.wrap("ak"), Bytes.wrap("vk")),
                        List.of(new NodePartyId(0L, schemeParty, 1L))))
                .build();
        return Network.newBuilder()
                .tssMetadata(TssMetadata.newBuilder().crsState(crs).activeHintsConstruction(construction))
                .nodeTssMetadata(NodeTssMetadata.newBuilder()
                        .nodeId(0L)
                        .partyId(hintParty)
                        .hintsKey(Bytes.wrap("hint"))
                        .build())
                .build();
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
                () -> TssStartupNetworks.initializeHintsState(mock(WritableStates.class), unfinished, config("DEV")));
        final var mismatched = unfinished
                .copyBuilder()
                .tssMetadata(TssMetadata.newBuilder()
                        .activeHintsConstruction(construction)
                        .crsState(crs.copyBuilder().ceremonyId(3L).stage(CRSStage.COMPLETED)))
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> TssStartupNetworks.initializeHintsState(mock(WritableStates.class), mismatched, config("DEV")));
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

    @ParameterizedTest
    @ValueSource(strings = {"DEV", "TEST"})
    void writesStartupNetworkPrivateKeysUnderNonProdProfile(final String profile) {
        final var config = config(profile);

        TssStartupNetworks.writePrivateKeys(networkWithSelfPrivateKeys(), config, SELF_NODE_ID);

        assertThat(TssKeyFiles.readBlsPrivateKey(config, CONSTRUCTION_ID)).contains(BLS_PRIVATE_KEY);
        assertThat(TssKeyFiles.readSchnorrKeyPair(config, CONSTRUCTION_ID)).contains(SCHNORR_KEY_PAIR);
    }

    @Test
    void doesNotWriteStartupNetworkPrivateKeysUnderProdProfile() {
        final var config = config("PROD");
        final var existingBlsKey = Bytes.wrap("existing-bls-key");
        final var existingSchnorrKeys =
                new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("existing-private"), Bytes.wrap("existing-public"));
        TssKeyFiles.writeBlsPrivateKey(config, CONSTRUCTION_ID, existingBlsKey);
        TssKeyFiles.writeSchnorrKeyPair(config, CONSTRUCTION_ID, existingSchnorrKeys);

        TssStartupNetworks.writePrivateKeys(networkWithSelfPrivateKeys(), config, SELF_NODE_ID);

        assertThat(TssKeyFiles.readBlsPrivateKey(config, CONSTRUCTION_ID)).contains(existingBlsKey);
        assertThat(TssKeyFiles.readSchnorrKeyPair(config, CONSTRUCTION_ID)).contains(existingSchnorrKeys);
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
