// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.keccak256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RlpDecoderTest {

    @Nested
    class DecodeAccount {

        @Test
        void decodeAccount_extractsStorageRootAndCodeHash() {
            byte[] storageRoot = bytes(32, 0x10);
            byte[] codeHash = bytes(32, 0x20);
            byte[] accountRlp = Rlp.encodeList(List.of(
                    Rlp.encodeUint(0L), Rlp.encodeUint(0L), Rlp.encodeBytes(storageRoot), Rlp.encodeBytes(codeHash)));

            EvmAccount account = RlpDecoder.decodeAccount(accountRlp);
            assertThat(account.storageRoot32()).isEqualTo(storageRoot);
            assertThat(account.codeHash32()).isEqualTo(codeHash);
        }

        @Test
        void decodeAccount_wrongFieldCount_throws() {
            byte[] accountRlp = Rlp.encodeList(
                    List.of(Rlp.encodeUint(0L), Rlp.encodeBytes(bytes(32, 1)), Rlp.encodeBytes(bytes(32, 2))));
            assertThatThrownBy(() -> RlpDecoder.decodeAccount(accountRlp))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("account value is not RLP");
        }

        @Test
        void decodeAccount_notAList_throws() {
            assertThatThrownBy(() -> RlpDecoder.decodeAccount(Rlp.encodeBytes(bytes(4, 1))))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("account value is not RLP");
        }
    }

    @Nested
    class DecodeMerklePatriciaTrieProvenValue {

        @Test
        void singleLeafProof_provesValue() {
            byte[] key = bytes(32, 0xAA);
            byte[] value = bytes(5, 0x33);
            byte[] leaf = leafNode(key, value);

            MerklePatriciaTrie trie = RlpDecoder.decodeMerklePatriciaTrie(keccak256(leaf), new byte[][] {leaf});
            assertThat(trie.provenValue(key)).contains(value);
        }

        @Test
        void singleLeafProof_divergingKey_provesAbsent() {
            byte[] leaf = leafNode(bytes(32, 0xAA), bytes(5, 0x33));
            MerklePatriciaTrie trie = RlpDecoder.decodeMerklePatriciaTrie(keccak256(leaf), new byte[][] {leaf});
            assertThat(trie.provenValue(bytes(32, 0xBB))).isEmpty();
        }

        @Test
        void emptyTrieRoot_provesAbsent() {
            byte[] emptyRoot = keccak256(Rlp.encodeBytes(new byte[0])); // keccak256(0x80)
            MerklePatriciaTrie trie = RlpDecoder.decodeMerklePatriciaTrie(emptyRoot, new byte[0][]);
            assertThat(trie.provenValue(bytes(32, 1))).isEmpty();
        }

        @Test
        void multiLevelProof_extensionBranchLeaves_provesValuesAndExclusion() {
            // A complete proof rooted at a real keccak hash: extension (shared nibble 1) → branch
            // (diverges at nibble 2/3) → two leaves, each referenced by the keccak of its RLP encoding.
            byte[] keyA = new byte[32];
            keyA[0] = 0x12; // nibbles [1,2,0,...]
            byte[] keyB = new byte[32];
            keyB[0] = 0x13; // nibbles [1,3,0,...]
            byte[] valueA = bytes(5, 0x33);
            byte[] valueB = bytes(7, 0x44);
            int[] nibblesA = ProofBytes.toNibbles(keyA);
            int[] nibblesB = ProofBytes.toNibbles(keyB);

            // each leaf consumes the key nibbles left after the extension(1) + branch(1).
            byte[] leafA = leafNodeWithPath(Arrays.copyOfRange(nibblesA, 2, 64), valueA);
            byte[] leafB = leafNodeWithPath(Arrays.copyOfRange(nibblesB, 2, 64), valueB);

            Map<Integer, byte[]> children = new HashMap<>();
            children.put(nibblesA[1], keccak256(leafA)); // slot 2
            children.put(nibblesB[1], keccak256(leafB)); // slot 3
            byte[] branch = branchNode(children);

            byte[] root = extensionNode(new int[] {nibblesA[0]}, keccak256(branch));

            MerklePatriciaTrie trie =
                    RlpDecoder.decodeMerklePatriciaTrie(keccak256(root), new byte[][] {root, branch, leafA, leafB});
            assertThat(trie.provenValue(keyA)).contains(valueA);
            assertThat(trie.provenValue(keyB)).contains(valueB);
            // shares the extension prefix but lands on an empty branch slot.
            byte[] keyC = new byte[32];
            keyC[0] = 0x14; // nibble[1] = 4 → unpopulated slot
            assertThat(trie.provenValue(keyC)).isEmpty();
        }
    }

    @Nested
    class DecodeMerklePatriciaTrieStructuralErrors {

        @Test
        void emptyProofNode_throws() {
            assertThatThrownBy(() -> RlpDecoder.decodeMerklePatriciaTrie(bytes(32, 1), new byte[][] {new byte[0]}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("empty proof node");
        }

        @Test
        void nodeNotAList_throws() {
            byte[] node = Rlp.encodeBytes(bytes(3, 1));
            assertThatThrownBy(() -> RlpDecoder.decodeMerklePatriciaTrie(keccak256(node), new byte[][] {node}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("MPT node is not an RLP list");
        }

        @Test
        void invalidNodeItemCount_throws() {
            byte[] node = Rlp.encodeList(
                    List.of(Rlp.encodeBytes(bytes(1, 1)), Rlp.encodeBytes(bytes(1, 2)), Rlp.encodeBytes(bytes(1, 3))));
            assertThatThrownBy(() -> RlpDecoder.decodeMerklePatriciaTrie(keccak256(node), new byte[][] {node}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid MPT node length 3");
        }

        @Test
        void emptyHexPrefixPath_throws() {
            byte[] node = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[0]), Rlp.encodeBytes(bytes(2, 1))));
            assertThatThrownBy(() -> RlpDecoder.decodeMerklePatriciaTrie(keccak256(node), new byte[][] {node}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("empty hex-prefix path");
        }

        @Test
        void invalidEvenHexPrefix_throws() {
            // 0x21: even flag (leaf) but a non-zero low nibble, which an even path forbids.
            byte[] node = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[] {0x21}), Rlp.encodeBytes(bytes(2, 1))));
            assertThatThrownBy(() -> RlpDecoder.decodeMerklePatriciaTrie(keccak256(node), new byte[][] {node}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid even hex-prefix path");
        }

        @Test
        void invalidChildReferenceLength_throws() {
            List<byte[]> items = new ArrayList<>();
            items.add(Rlp.encodeBytes(new byte[33])); // child[0] is neither a 32-byte hash nor an inline node
            for (int i = 1; i < 16; i++) {
                items.add(Rlp.encodeBytes(new byte[0]));
            }
            items.add(Rlp.encodeBytes(new byte[0])); // branch value
            byte[] branch = Rlp.encodeList(items);

            assertThatThrownBy(() -> RlpDecoder.decodeMerklePatriciaTrie(keccak256(branch), new byte[][] {branch}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid MPT node reference length 33");
        }
    }

    // ── helpers ──

    /** A single even-path leaf whose 64-nibble path equals {@code key32}, storing {@code value}. */
    private static byte[] leafNode(byte[] key32, byte[] value) {
        byte[] hexPrefix = new byte[33];
        hexPrefix[0] = 0x20; // leaf, even
        System.arraycopy(key32, 0, hexPrefix, 1, 32);
        return Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix), Rlp.encodeBytes(value)));
    }

    /** A leaf carrying an arbitrary remaining-path nibble segment. */
    private static byte[] leafNodeWithPath(int[] path, byte[] value) {
        return Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix(path, true)), Rlp.encodeBytes(value)));
    }

    /** An extension over {@code path} referencing a child node by its 32-byte hash. */
    private static byte[] extensionNode(int[] path, byte[] childHash32) {
        return Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix(path, false)), Rlp.encodeBytes(childHash32)));
    }

    /** A 17-item branch: slots in {@code childHashes} hold a 32-byte hash, the rest are empty. */
    private static byte[] branchNode(Map<Integer, byte[]> childHashes) {
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            byte[] h = childHashes.get(i);
            items.add(Rlp.encodeBytes(h == null ? new byte[0] : h));
        }
        items.add(Rlp.encodeBytes(new byte[0])); // no value at the branch itself
        return Rlp.encodeList(items);
    }

    /** Encodes {@code path} nibbles with the hex-prefix flag (leaf vs extension, odd vs even). */
    private static byte[] hexPrefix(int[] path, boolean leaf) {
        boolean odd = (path.length & 1) == 1;
        int flag = (leaf ? 2 : 0) | (odd ? 1 : 0);
        int[] nibbles;
        if (odd) {
            nibbles = new int[path.length + 1];
            nibbles[0] = flag;
            System.arraycopy(path, 0, nibbles, 1, path.length);
        } else {
            nibbles = new int[path.length + 2];
            nibbles[0] = flag; // nibbles[1] stays 0, as an even path requires
            System.arraycopy(path, 0, nibbles, 2, path.length);
        }
        byte[] out = new byte[nibbles.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
        }
        return out;
    }

    private static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
