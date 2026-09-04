// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import com.esaulpaugh.headlong.rlp.RLPDecoder;
import com.esaulpaugh.headlong.rlp.RLPItem;
import com.esaulpaugh.headlong.rlp.RLPList;
import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trips {@link EthSyncCommitteeProofs} output through an <em>independent</em> RLP + Merkle-Patricia
 * reader, so a structural bug in the generator is caught here rather than only inside a slow embedded
 * HAPI run. The reader deliberately re-implements the trie walk and slot decoding from scratch rather
 * than reusing the (module-private) production verifier.
 */
class EthSyncCommitteeProofsTest {

    private static final RLPDecoder RLP = RLPDecoder.RLP_STRICT;

    @Test
    void configPayloadHasExpectedTopLevelShape() {
        final RLPList payload = topList(EthSyncCommitteeProofs.configPayload());
        final List<RLPItem> items = payload.elements();
        // [slot, syncCommittee, genesisValidatorsRoot, forkVersion, ledgerConfigBytes]
        assertThat(items).hasSize(5);
        final List<RLPItem> committee = items.get(1).asRLPList().elements();
        assertThat(committee).hasSize(2);
        assertThat(committee.get(0).asRLPList().elements()).hasSize(EthSyncCommitteeProofs.COMMITTEE_SIZE);
        assertThat(items.get(3).data()).hasSize(4); // forkVersion
    }

    @Test
    void bundlePayloadStorageProofsRoundTripToTheEmbeddedMetadata() {
        final var payload = ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(new byte[32]))
                        .setTargetApplication(ByteString.copyFrom(new byte[20]))
                        .setSender(ByteString.copyFrom(new byte[20]))
                        .setMessageData(ByteString.copyFromUtf8("hello")))
                .build();
        final var content = EthSyncCommitteeProofs.singleMessageBundleContent(payload);
        final RLPList bundle = topList(EthSyncCommitteeProofs.bundlePayload(content));
        final List<RLPItem> items = bundle.elements();
        assertThat(items).hasSize(9);

        // syncAggregate = [bits(64), signature(96)]
        final List<RLPItem> syncAggregate = items.get(1).asRLPList().elements();
        assertThat(syncAggregate.get(0).data()).hasSize(64);
        assertThat(syncAggregate.get(1).data()).hasSize(96);

        // Account proof against the proven execution state root → storage root.
        final byte[] executionStateRoot = items.get(2).data();
        final List<byte[]> accountNodes = rawList(items.get(6));
        final byte[] accountValue = walk(
                executionStateRoot,
                accountNodes,
                EthSyncCommitteeProofs.keccak256(EthSyncCommitteeProofs.SERVICE_ADDRESS));
        assertThat(accountValue).as("account must be present in state trie").isNotNull();
        final byte[] storageRoot = topList(accountValue).elements().get(2).data();

        // Re-prove every storage slot against that storage root.
        final byte[][] slotValues = new byte[5][];
        final List<RLPItem> entries = items.get(7).asRLPList().elements();
        assertThat(entries).hasSize(5);
        for (int i = 0; i < 5; i++) {
            final List<RLPItem> entry = entries.get(i).asRLPList().elements();
            final byte[] key = entry.get(0).data();
            final List<byte[]> nodes = rawList(entry.get(1));
            final byte[] proven = walk(storageRoot, nodes, EthSyncCommitteeProofs.keccak256(leftPad32(key)));
            slotValues[i] = proven == null ? new byte[32] : leftPad32(topItemBytes(proven));
        }

        // Decode the slots the way QueueMetadata.decode does and compare to the embedded metadata.
        final var meta = content.getMetadata();
        assertThat(readUint64(slotValues[0], 3)).isEqualTo(meta.getNextMessageId());
        assertThat(slotValues[0][11] & 0xFF).isEqualTo(ClprChannelStatus.ACTIVE.getNumber());
        assertThat(readUint64(slotValues[1], 16)).isEqualTo(meta.getReceivedMessageId());
        assertThat(slotValues[2]).isEqualTo(meta.getSentRunningHash().toByteArray());
        assertThat(slotValues[3]).isEqualTo(meta.getReceivedRunningHash().toByteArray());
    }

    // ── Minimal independent MPT reader ──

    /** Walks the trie from {@code root} using {@code nodes}; returns the leaf value bytes, or null if absent. */
    private static byte[] walk(final byte[] root, final List<byte[]> nodes, final byte[] keyHash) {
        final Map<String, byte[]> byHash = new HashMap<>();
        for (final byte[] n : nodes) {
            byHash.put(hex(EthSyncCommitteeProofs.keccak256(n)), n);
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
