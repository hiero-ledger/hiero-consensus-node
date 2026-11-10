// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE;
import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import com.swirlds.state.State;
import com.swirlds.state.binary.MerkleProof;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Exercises compact bundle packing from the snapshot's binary-state API through the emitted envelope. */
@ExtendWith(MockitoExtension.class)
class ClprBundleProofConstructionTest {
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final long FIRST_MESSAGE_ID = 11;
    private static final long CHANNEL_PATH = 1;
    private static final long MANIFEST_PATH = 2;
    private static final Bytes SIGNING_KEY = verificationKey(2);
    private static final Bytes PREDECESSOR_KEY = verificationKey(1);
    private static final Bytes AGGREGATE = Bytes.wrap(new byte[SIGNATURE_LENGTH]);
    private static final Bytes NATIVE_SIGNATURE = SIGNING_KEY.append(AGGREGATE).append(Bytes.wrap(new byte[11_368]));

    @Mock
    private BlockProvenSnapshotProvider snapshotProvider;

    @Mock
    private BlockProvenSnapshot snapshot;

    @Mock(extraInterfaces = BinaryState.class)
    private State provenState;

    @Mock
    private TssVerifier tssVerifier;

    @Mock
    private ClprHieroProofSupport proofSupport;

    private ClprStateProofManager proofManager;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.digestType", "SHA_256")
                .getOrCreateConfig();
        proofManager = new ClprStateProofManager(
                snapshotProvider, tssVerifier, () -> new VersionedConfigImpl(config, 0), proofSupport);
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(provenState);
        given(snapshot.blockTimestamp()).willReturn(new Timestamp(2, 0));
        given(snapshot.path()).willReturn(MerklePath.DEFAULT);
        given(snapshot.tssSignature()).willReturn(NATIVE_SIGNATURE);
        given(proofSupport.verify(any(), any())).willReturn(true);
    }

    @ParameterizedTest
    @CsvSource({"false, 63", "true, 62"})
    void capsPathsWithMessagesStillQueued(final boolean includeManifest, final int expectedMessages) throws Exception {
        final var channel = channel(70, 1_048_576);
        givenQueue(channel, 1, includeManifest);
        final var producer = producer(channel, SIGNING_KEY);

        final var payload = producer.next(includeManifest);

        final var proof =
                ClprHieroProofCodec.decodeEnvelope(requireNonNull(payload)).stateProofOrThrow();
        assertThat(proof.paths()).hasSize(64);
        assertThat(proof.signedBlockProofOrThrow().blockSignature()).isEqualTo(SIGNING_KEY.append(AGGREGATE));
        final var items = new ArrayList<StateItem>();
        for (final var path : proof.paths()) {
            items.add(StateItem.PROTOBUF.parse(path.stateItemLeafOrThrow()));
        }
        assertThat(items.getFirst().valueOrThrow().clprServiceIChannelsOrThrow())
                .isEqualTo(channel);
        assertThat(items)
                .filteredOn(item -> item.valueOrThrow().hasClprServiceIEndpointManifest())
                .hasSize(includeManifest ? 1 : 0);
        assertThat(items.stream()
                        .filter(item -> item.keyOrThrow().hasClprServiceIMessageQueue())
                        .map(item -> item.keyOrThrow()
                                .clprServiceIMessageQueueOrThrow()
                                .messageId()))
                .containsExactlyElementsOf(LongStream.range(FIRST_MESSAGE_ID, FIRST_MESSAGE_ID + expectedMessages)
                        .boxed()
                        .toList());
        assertThat(producer.nextStartingMessageId()).isEqualTo(FIRST_MESSAGE_ID + expectedMessages);
        verify(proofSupport).verify(payload, keccak256DigestOf(SIGNING_KEY));
        verifyNoInteractions(tssVerifier);
    }

    @Test
    void sendsWitnessWhenTheInitiatorQueueIsEmpty() throws Exception {
        final var channel = channel(0, 8_192);
        givenQueue(channel, 1, false);
        givenRetainedWitness();

        assertWitnessOnly(channel);

        verify((BinaryState) provenState).getKvPath(MESSAGE_QUEUE_STATE_ID, messageKeyBytes(FIRST_MESSAGE_ID));
        verify((BinaryState) provenState, never()).getMerkleProof(FIRST_MESSAGE_ID);
    }

    @Test
    void sendsWitnessWhenTheHeadMessageExceedsTheByteBudget() throws Exception {
        final var channel = channel(2, 8_192);
        givenQueue(channel, 4_096, false);
        givenRetainedWitness();

        assertWitnessOnly(channel);

        final var binaryState = (BinaryState) provenState;
        verify(binaryState).getMerkleProof(FIRST_MESSAGE_ID);
        verify(binaryState, never()).getKvPath(MESSAGE_QUEUE_STATE_ID, messageKeyBytes(FIRST_MESSAGE_ID + 1));
    }

    private void assertWitnessOnly(final ClprChannel channel) throws Exception {
        final var producer = producer(channel, PREDECESSOR_KEY);
        final var payload = requireNonNull(producer.next(false));
        final var envelope = ClprHieroProofCodec.decodeEnvelope(payload);

        assertThat(envelope.hasBlockWitness()).isTrue();
        assertThat(envelope.hasStateProof()).isFalse();
        final var provenance = envelope.keyProvenanceOrThrow();
        assertThat(provenance.initialVerificationKey()).isEqualTo(PREDECESSOR_KEY);
        assertThat(provenance.links())
                .singleElement()
                .satisfies(link -> assertThat(link.nextVerificationKey()).isEqualTo(SIGNING_KEY));
        assertThat(payload.length())
                .isLessThanOrEqualTo(ClprStateProofManager.maxBundlePayloadBytes(channel.peerThrottlesOrThrow()));
        assertThat(producer.nextStartingMessageId()).isEqualTo(FIRST_MESSAGE_ID);
        assertThat(producer.next(false)).isNull();
        verify(proofSupport).verify(payload, keccak256DigestOf(PREDECESSOR_KEY));
        verify(snapshot).close();
        verifyNoInteractions(tssVerifier);
    }

    private ClprBundleProducer producer(final ClprChannel channel, final Bytes anchorKey) {
        final var request = ClprBundleRequest.newBuilder()
                .currentTrustAnchorId(Bytes.fromHex("01").append(keccak256DigestOf(anchorKey)))
                .currentStatus(ACTIVE)
                .currentReceivedMessageId(FIRST_MESSAGE_ID - 1)
                .build();
        return new ClprBundleProducer(proofManager, channel, request, false, 2);
    }

    private void givenRetainedWitness() {
        final var provenance = HintsKeyProvenance.newBuilder()
                .sourceVerificationKey(PREDECESSOR_KEY)
                .targetVerificationKey(SIGNING_KEY)
                .targetHintsConstructionId(1)
                .signature(AGGREGATE)
                .build();
        final var witness = HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(1)
                .timestamp(new Timestamp(1, 0))
                .siblingHash(Bytes.wrap(new byte[32]))
                .signature(AGGREGATE)
                .build();
        given(snapshot.keyProvenanceWithWitnesses())
                .willReturn(List.of(HintsKeyWitnessRecord.newBuilder()
                        .provenance(provenance)
                        .witness(witness)
                        .build()));
    }

    private void givenQueue(final ClprChannel channel, final int messageBytes, final boolean includeManifest) {
        final var binaryState = (BinaryState) provenState;
        final var channelKey = new ProtoBytes(CHANNEL_ID);
        final Map<Long, MerkleProof> proofs = new HashMap<>();
        proofs.put(
                CHANNEL_PATH,
                leaf(
                        StateKey.newBuilder().clprServiceIChannels(channelKey).build(),
                        StateValue.newBuilder().clprServiceIChannels(channel).build()));
        given(binaryState.getKvPath(CHANNELS_STATE_ID, ProtoBytes.PROTOBUF.toBytes(channelKey)))
                .willReturn(CHANNEL_PATH);
        if (includeManifest) {
            proofs.put(
                    MANIFEST_PATH,
                    leaf(
                            StateKey.newBuilder()
                                    .singleton(SingletonType.CLPRSERVICE_I_ENDPOINT_MANIFEST)
                                    .build(),
                            StateValue.newBuilder()
                                    .clprServiceIEndpointManifest(ClprEndpointManifest.newBuilder()
                                            .version(2)
                                            .build())
                                    .build()));
            given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willReturn(MANIFEST_PATH);
        }
        final var value = ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder().messageData(Bytes.wrap(new byte[messageBytes]))))
                .build();
        for (long id = FIRST_MESSAGE_ID; id < channel.nextMessageId(); id++) {
            proofs.put(
                    id,
                    leaf(
                            StateKey.newBuilder()
                                    .clprServiceIMessageQueue(messageKey(id))
                                    .build(),
                            StateValue.newBuilder()
                                    .clprServiceIMessageQueue(value)
                                    .build()));
        }
        given(binaryState.getKvPath(eq(MESSAGE_QUEUE_STATE_ID), any())).willAnswer(invocation -> {
            final var key = ClprMessageKey.PROTOBUF.parse((Bytes) invocation.getArgument(1));
            return CHANNEL_ID.equals(key.channelId())
                            && key.messageId() >= FIRST_MESSAGE_ID
                            && key.messageId() < channel.nextMessageId()
                    ? key.messageId()
                    : -1L;
        });
        given(binaryState.getMerkleProof(anyLong())).willAnswer(invocation -> proofs.get(invocation.getArgument(0)));
    }

    private static ClprChannel channel(final int queuedMessages, final long maxSyncBytes) {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ACTIVE)
                .ackedMessageId(FIRST_MESSAGE_ID - 1)
                .nextMessageId(FIRST_MESSAGE_ID + queuedMessages)
                .peerThrottles(
                        ClprThrottles.newBuilder().maxMessagesPerBundle(100).maxSyncBytes(maxSyncBytes))
                .build();
    }

    private static ClprMessageKey messageKey(final long id) {
        return new ClprMessageKey(CHANNEL_ID, id);
    }

    private static Bytes messageKeyBytes(final long id) {
        return ClprMessageKey.PROTOBUF.toBytes(messageKey(id));
    }

    private static MerkleProof leaf(final StateKey key, final StateValue value) {
        return new MerkleProof(StateItem.PROTOBUF.toBytes(new StateItem(key, value)), List.of(), List.of());
    }

    private static Bytes verificationKey(final int tag) {
        final var bytes = new byte[VK_LENGTH];
        bytes[0] = (byte) tag;
        return Bytes.wrap(bytes);
    }
}
