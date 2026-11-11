// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.esaulpaugh.headlong.rlp.RLPDecoder;
import com.esaulpaugh.headlong.rlp.RLPItem;
import com.esaulpaugh.headlong.rlp.RLPList;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumSyncCommitteeProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.NativeBlsSignatureVerifier;
import com.hedera.services.bdd.suites.clpr.ethereum.SyncCommitteeProofs.Preset;
import com.hederahashgraph.api.proto.java.ClprBundleContent;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprQueueMetadata;
import com.hederahashgraph.api.proto.java.EthereumPosLedgerConfigurationPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Round-trips {@link SyncCommitteeProofs} output through an <em>independent</em> RLP + Merkle-Patricia
 * reader, so a structural bug in the generator is caught here rather than only inside a slow embedded
 * HAPI run. The reader deliberately re-implements the trie walk and slot decoding from scratch rather
 * than reusing the (module-private) production verifier.
 */
class SyncCommitteeProofsTest {

    private static final RLPDecoder RLP = RLPDecoder.RLP_STRICT;

    private static final byte[] CHANNEL_ID =
            HexFormat.of().parseHex("3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8c7e60524436281a0bfdefd");

    /** {@code keccak256(CHANNEL_ID ‖ uint256(15)) + 1}, computed with foundry's {@code cast}. */
    private static final String FIRST_CHANNEL_SLOT = "44637163277d9a932c1ffaff31b12f7a1ef43c674d0f3387a8f8e6864b1628f4";

    @Test
    @DisplayName("given each preset, then configPayload carries a bootstrap and an update of that preset's size")
    void givenEachPreset_thenConfigPayloadCarriesBootstrapAndUpdateOfPresetSize() throws Exception {
        for (final Preset preset : Preset.values()) {
            final var payload =
                    EthereumPosLedgerConfigurationPayload.parseFrom(SyncCommitteeProofs.configPayload(preset));

            assertThat(payload.getSyncCommittee().getPubkeysList())
                    .hasSize(preset.committeeSize)
                    .allSatisfy(key -> assertThat(key.size()).isEqualTo(48));
            assertThat(payload.getSyncCommittee().getAggregatePubkey().size()).isEqualTo(48);
            assertThat(payload.getNextSyncCommittee().getPubkeysList()).hasSize(preset.committeeSize);
            assertThat(payload.getAnchorHeader().getSlot()).isEqualTo(payload.getSlot());
            assertThat(payload.getCurrentSyncCommitteeBranchList()).hasSize(11);
            assertThat(payload.getNextSyncCommitteeBranchList()).hasSize(11);
            assertThat(payload.getSyncAggregate().getBits().size()).isEqualTo(preset.committeeSize / 8);
            assertThat(payload.getSyncAggregate().getSignature().size()).isEqualTo(256);
            assertThat(payload.getCodeHash().size()).isEqualTo(32);
            assertThat(payload.getGenesisValidatorsRoot().size()).isEqualTo(32);
            assertThat(payload.getForkVersion().size()).isEqualTo(4);
            assertThat(payload.getLedgerConfiguration().getServiceAddress().toByteArray())
                    .isEqualTo(SyncCommitteeProofs.SERVICE_ADDRESS);
        }
    }

    @Test
    @DisplayName("given a rotation bundle, then it carries one 7-item rotation and an 11-item delivery")
    void givenRotationBundle_thenCarriesOneRotationAndDelivery() {
        final var content = SyncCommitteeProofs.singleMessageBundleContent(message());
        final List<RLPItem> items = topList(SyncCommitteeProofs.rotationBundlePayload(CHANNEL_ID, content))
                .elements();

        final List<RLPItem> rotations = items.get(0).asRLPList().elements();
        assertThat(rotations).hasSize(1);
        final List<RLPItem> rotation = rotations.get(0).asRLPList().elements();
        assertThat(rotation).hasSize(7);
        assertThat(rotation.get(4).asRLPList().elements()).hasSize(9); // finality branch
        final List<RLPItem> nextCommittee = rotation.get(5).asRLPList().elements();
        assertThat(nextCommittee.get(0).data()).hasSize(32); // pubkeysRoot
        assertThat(nextCommittee.get(1).data()).hasSize(128); // uncompressed aggregate
        assertThat(rotation.get(6).asRLPList().elements()).hasSize(11); // next committee branch
        assertThat(items.get(1).asRLPList().elements()).hasSize(11);
    }

    @Test
    @DisplayName("given a bundle, then its storage proofs of the derived Channel slots round-trip to the embedded"
            + " metadata")
    void givenBundle_thenStorageProofsRoundTripToEmbeddedMetadata() {
        final var content = SyncCommitteeProofs.singleMessageBundleContent(message());
        final List<RLPItem> items =
                topList(SyncCommitteeProofs.bundlePayload(CHANNEL_ID, content)).elements();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).asRLPList().elements()).isEmpty();
        final List<RLPItem> delivery = items.get(1).asRLPList().elements();
        assertThat(delivery).hasSize(11);

        // The committee signs in the slot after the attested header.
        final long attestedSlot = delivery.get(0).asRLPList().elements().get(0).asLong();
        assertThat(delivery.get(1).asLong()).isEqualTo(attestedSlot + 1);

        // syncAggregate = [bits(64), signature(256)]; two non-signers of 128 + 9 × 32 bytes each.
        final List<RLPItem> syncAggregate = delivery.get(2).asRLPList().elements();
        assertThat(syncAggregate.get(0).data()).hasSize(64);
        assertThat(syncAggregate.get(1).data()).hasSize(256);
        assertThat(delivery.get(3).asRLPList().elements())
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.data()).hasSize(416));

        // The execution state root is item 3 of the block header in finalizedExecution.
        final byte[] blockHeaderRlp =
                delivery.get(4).asRLPList().elements().get(2).data();
        final byte[] executionStateRoot =
                topList(blockHeaderRlp).elements().get(3).data();

        // Account proof against the execution state root → storage root.
        final byte[] accountValue = walk(
                executionStateRoot,
                rawList(delivery.get(6)),
                MiscCryptoUtils.keccak256DigestOf(SyncCommitteeProofs.SERVICE_ADDRESS));
        assertThat(accountValue).as("account must be present in state trie").isNotNull();
        final byte[] storageRoot = topList(accountValue).elements().get(2).data();

        // Re-prove every storage slot against that storage root.
        final byte[][] slotValues = new byte[6][];
        final List<RLPItem> entries = delivery.get(7).asRLPList().elements();
        assertThat(entries).hasSize(6);
        assertThat(HexFormat.of()
                        .formatHex(entries.get(0).asRLPList().elements().get(0).data()))
                .isEqualTo(FIRST_CHANNEL_SLOT);
        for (int i = 0; i < 6; i++) {
            final List<RLPItem> entry = entries.get(i).asRLPList().elements();
            final byte[] key = entry.get(0).data();
            final List<byte[]> nodes = rawList(entry.get(1));
            final byte[] proven = walk(storageRoot, nodes, MiscCryptoUtils.keccak256DigestOf(leftPad32(key)));
            slotValues[i] = proven == null ? new byte[32] : leftPad32(topItemBytes(proven));
        }

        // Decode the slots the way QueueMetadata.decode does and compare to the embedded metadata.
        final var meta = content.getMetadata();
        assertThat(readUint64(slotValues[0], 3)).isEqualTo(meta.getNextMessageId());
        assertThat(slotValues[0][11] & 0xFF).isEqualTo(ClprChannelStatus.ACTIVE.getNumber());
        assertThat(readUint64(slotValues[1], 16)).isEqualTo(meta.getReceivedMessageId());
        assertThat(slotValues[2]).isEqualTo(meta.getSentRunningHash().toByteArray());
        assertThat(slotValues[3]).isEqualTo(meta.getReceivedRunningHash().toByteArray());
        assertThat(readUint64(slotValues[4], 24)).isEqualTo(meta.getEndpointManifestVersion());
        assertThat(slotValues[5]).isEqualTo(meta.getSentRunningHash().toByteArray());
    }

    @Test
    void generatedProofVerifiesOnlyForItsRequestedChannel() {
        final var content = messageContent();
        final byte[] channelId = new byte[32];
        channelId[0] = 0x42;
        final byte[] context = channelContext(channelId);
        final var verifier = new EthereumSyncCommitteeProofVerifier(NativeBlsSignatureVerifier.INSTANCE);
        final byte[] anchor = verifier.verifyConfigPayload(SyncCommitteeProofs.configPayload())
                .ledgerConfiguration()
                .initialTrustAnchor()
                .toByteArray();
        final byte[] bundle = SyncCommitteeProofs.bundlePayload(channelId, content);

        final var verified = verifier.verifyBundle(bundle, anchor, context);

        assertThat(verified.queueMetadata().nextMessageId())
                .isEqualTo(content.getMetadata().getNextMessageId());
        assertThat(verified.queueMetadata().lastMessageRunningHash())
                .isEqualTo(content.getMetadata().getSentRunningHash().toByteArray());
        final byte[] otherContext = context.clone();
        otherContext[0] ^= 1;
        assertThatThrownBy(() -> verifier.verifyBundle(bundle, anchor, otherContext))
                .isInstanceOf(ProofException.class);
    }

    @Test
    void tamperedAccountProofStillFailsWithCanonicalChannelKeys() {
        final byte[] channelId = new byte[32];
        final var verifier = new EthereumSyncCommitteeProofVerifier(NativeBlsSignatureVerifier.INSTANCE);
        final byte[] anchor = verifier.verifyConfigPayload(SyncCommitteeProofs.configPayload())
                .ledgerConfiguration()
                .initialTrustAnchor()
                .toByteArray();
        final byte[] bundle = SyncCommitteeProofs.tamperedBundlePayload(channelId, messageContent());

        assertThatThrownBy(() -> verifier.verifyBundle(bundle, anchor, channelContext(channelId)))
                .isInstanceOf(ProofException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void metadataWithoutSentMessagesKeepsFiveChannelProofs(final long nextMessageId) {
        final var metadata = ClprQueueMetadata.newBuilder()
                .setNextMessageId(nextMessageId)
                .setStatus(ClprChannelStatus.ACTIVE)
                .setSentRunningHash(ByteString.copyFrom(new byte[32]))
                .setReceivedRunningHash(ByteString.copyFrom(new byte[32]))
                .setEndpointManifestVersion(7)
                .build();
        final var content = ClprBundleContent.newBuilder().setMetadata(metadata).build();
        final byte[] bundle = SyncCommitteeProofs.bundlePayload(CHANNEL_ID, content);
        final List<RLPItem> delivery =
                topList(bundle).elements().get(1).asRLPList().elements();
        assertThat(delivery.get(7).asRLPList().elements()).hasSize(5);
        final var verifier = new EthereumSyncCommitteeProofVerifier(NativeBlsSignatureVerifier.INSTANCE);
        final byte[] anchor = verifier.verifyConfigPayload(SyncCommitteeProofs.configPayload())
                .ledgerConfiguration()
                .initialTrustAnchor()
                .toByteArray();

        final var verified = verifier.verifyBundle(bundle, anchor, channelContext(CHANNEL_ID));

        assertThat(verified.queueMetadata().nextMessageId()).isEqualTo(nextMessageId);
        assertThat(verified.queueMetadata().status()).isEqualTo(ClprChannelStatus.ACTIVE.getNumber());
        assertThat(verified.queueMetadata().endpointManifestVersion()).isEqualTo(7);
    }

    private static byte[] channelContext(final byte[] channelId) {
        final byte[] context = new byte[52];
        System.arraycopy(channelId, 0, context, 0, channelId.length);
        System.arraycopy(SyncCommitteeProofs.SERVICE_ADDRESS, 0, context, 32, 20);
        return context;
    }

    private static ClprBundleContent messageContent() {
        return SyncCommitteeProofs.singleMessageBundleContent(message());
    }

    private static ClprMessagePayload message() {
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(new byte[32]))
                        .setTargetApplication(ByteString.copyFrom(new byte[20]))
                        .setSender(ByteString.copyFrom(new byte[20]))
                        .setMessageData(ByteString.copyFromUtf8("hello")))
                .build();
    }

    // ── Minimal independent MPT reader ──

    /** Walks the trie from {@code root} using {@code nodes}; returns the leaf value bytes, or null if absent. */
    private static byte[] walk(final byte[] root, final List<byte[]> nodes, final byte[] keyHash) {
        final Map<String, byte[]> byHash = new HashMap<>();
        for (final byte[] n : nodes) {
            byHash.put(hex(MiscCryptoUtils.keccak256DigestOf(n)), n);
        }
        final int[] path = nibbles(keyHash);
        int pi = 0;
        byte[] cur = root;
        while (true) {
            // Looks up child references by keccak hash only. Per the MPT spec, references < 32 bytes are
            // inlined rather than hashed; the current test data has no such nodes (all are >= 32 bytes), so
            // this reader does not handle the inline case and would abort here if one appeared.
            final byte[] node = byHash.get(hex(cur));
            if (node == null) {
                return null;
            }
            final List<RLPItem> elems = topList(node).elements();
            if (elems.size() == 2) {
                final int[] hp = decodeHexPrefix(elems.get(0).data());
                final boolean leaf = (hp[0] & 2) != 0;
                final int[] nibs = java.util.Arrays.copyOfRange(hp, 1, hp.length);
                if (!matches(path, pi, nibs)) {
                    return null;
                }
                pi += nibs.length;
                if (leaf) {
                    return elems.get(1).data();
                }
                cur = elems.get(1).data();
            } else if (elems.size() == 17) {
                final byte[] child = elems.get(path[pi++]).data();
                if (child.length == 0) {
                    return null;
                }
                cur = child;
            } else {
                throw new AssertionError("unexpected node arity " + elems.size());
            }
        }
    }

    /** Decodes a hex-prefix into {@code [flagNibble, nibbles...]}. */
    private static int[] decodeHexPrefix(final byte[] hp) {
        final int firstNibble = (hp[0] >> 4) & 0x0F;
        final boolean odd = (firstNibble & 1) != 0;
        final List<Integer> nibs = new ArrayList<>();
        if (odd) {
            nibs.add(hp[0] & 0x0F);
        }
        for (int i = 1; i < hp.length; i++) {
            nibs.add((hp[i] >> 4) & 0x0F);
            nibs.add(hp[i] & 0x0F);
        }
        final int[] out = new int[1 + nibs.size()];
        out[0] = firstNibble;
        for (int i = 0; i < nibs.size(); i++) {
            out[i + 1] = nibs.get(i);
        }
        return out;
    }

    private static boolean matches(final int[] path, final int from, final int[] nibs) {
        if (from + nibs.length > path.length) {
            return false;
        }
        for (int i = 0; i < nibs.length; i++) {
            if (path[from + i] != nibs[i]) {
                return false;
            }
        }
        return true;
    }

    private static RLPList topList(final byte[] rlp) {
        return RLP.sequenceIterator(rlp).next().asRLPList();
    }

    private static byte[] topItemBytes(final byte[] rlp) {
        return RLP.sequenceIterator(rlp).next().data();
    }

    private static List<byte[]> rawList(final RLPItem item) {
        final List<byte[]> out = new ArrayList<>();
        for (final RLPItem e : item.asRLPList().elements()) {
            out.add(e.data());
        }
        return out;
    }

    private static int[] nibbles(final byte[] bytes) {
        final int[] out = new int[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = (bytes[i] >> 4) & 0x0F;
            out[2 * i + 1] = bytes[i] & 0x0F;
        }
        return out;
    }

    private static long readUint64(final byte[] buf, final int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[offset + i] & 0xFFL);
        }
        return v;
    }

    private static byte[] leftPad32(final byte[] value) {
        if (value.length == 32) {
            return value;
        }
        final byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 32 - value.length, value.length);
        return out;
    }

    private static String hex(final byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }
}
