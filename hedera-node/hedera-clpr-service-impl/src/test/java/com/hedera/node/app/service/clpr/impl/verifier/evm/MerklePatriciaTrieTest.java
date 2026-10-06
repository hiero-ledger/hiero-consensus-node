// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.BranchNode;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.ExtensionNode;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.LeafNode;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.NodeRef;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.TrieNode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link MerklePatriciaTrie#provenValue} traversal in isolation by building the typed
 * node graph directly — no RLP — so each branch/leaf/extension/inline/hash/exclusion path is
 * covered.
 */
class MerklePatriciaTrieTest {

    private static final byte[] KEY = key(0x11);
    private static final int[] KEY_NIBBLES = ProofBytes.toNibbles(KEY);
    private static final byte[] VALUE = bytes(7, 0xA0);

    // keccak256(RLP("")) = keccak256(0x80) — the canonical empty MPT root.
    private static final byte[] EMPTY_TRIE_ROOT = ProofBytes.keccak256(new byte[] {(byte) 0x80});

    @Test
    void emptyTrieRoot_provesEverythingAbsent() {
        MerklePatriciaTrie trie = new MerklePatriciaTrie(EMPTY_TRIE_ROOT, new HashMap<>());
        assertThat(trie.provenValue(KEY)).isEmpty();
    }

    @Test
    void missingRootNode_throws() {
        MerklePatriciaTrie trie = new MerklePatriciaTrie(hash(1), new HashMap<>());
        assertThatThrownBy(() -> trie.provenValue(KEY))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("missing proof node");
    }

    @Test
    void singleLeaf_matchingPath_returnsValue() {
        byte[] root = hash(1);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new LeafNode(KEY_NIBBLES, VALUE));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).contains(VALUE);
    }

    @Test
    void singleLeaf_divergingPath_provesAbsent() {
        byte[] root = hash(1);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        // leaf encodes a different key's path → the lookup key cannot match it.
        nodes.put(new BytesKey(root), new LeafNode(ProofBytes.toNibbles(key(0x22)), VALUE));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).isEmpty();
    }

    @Test
    void provenValue_returnsDefensiveCopy() {
        byte[] root = hash(1);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new LeafNode(KEY_NIBBLES, VALUE));
        MerklePatriciaTrie trie = new MerklePatriciaTrie(root, nodes);

        byte[] first = trie.provenValue(KEY).orElseThrow();
        first[0] = (byte) (first[0] + 1); // mutate the returned array
        assertThat(trie.provenValue(KEY)).contains(VALUE); // trie is unaffected
    }

    @Test
    void branch_keyConsumedAtBranch_returnsBranchValue() {
        // extension consuming all 64 nibbles → branch carrying the value at its own slot.
        byte[] root = hash(1);
        byte[] branchHash = hash(2);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new ExtensionNode(KEY_NIBBLES, new NodeRef(branchHash, null)));
        nodes.put(new BytesKey(branchHash), new BranchNode(new NodeRef[16], VALUE));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).contains(VALUE);
    }

    @Test
    void branch_keyConsumedAtValuelessBranch_provesAbsent() {
        byte[] root = hash(1);
        byte[] branchHash = hash(2);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new ExtensionNode(KEY_NIBBLES, new NodeRef(branchHash, null)));
        nodes.put(new BytesKey(branchHash), new BranchNode(new NodeRef[16], null));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).isEmpty();
    }

    @Test
    void branch_emptyChildSlot_provesAbsent() {
        byte[] root = hash(1);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new BranchNode(new NodeRef[16], null)); // slot for KEY's first nibble is null

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).isEmpty();
    }

    @Test
    void branch_toLeafByHashReference_returnsValue() {
        byte[] root = hash(1);
        byte[] leafHash = hash(2);
        NodeRef[] children = new NodeRef[16];
        children[KEY_NIBBLES[0]] = new NodeRef(leafHash, null);

        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new BranchNode(children, null));
        nodes.put(new BytesKey(leafHash), new LeafNode(tail(KEY_NIBBLES, 1), VALUE));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).contains(VALUE);
    }

    @Test
    void branch_toInlinedChild_returnsValue() {
        byte[] root = hash(1);
        NodeRef[] children = new NodeRef[16];
        children[KEY_NIBBLES[0]] = new NodeRef(null, new LeafNode(tail(KEY_NIBBLES, 1), VALUE));

        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new BranchNode(children, null));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).contains(VALUE);
    }

    @Test
    void extension_thenLeaf_returnsValue() {
        byte[] root = hash(1);
        byte[] leafHash = hash(2);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(
                new BytesKey(root),
                new ExtensionNode(Arrays.copyOfRange(KEY_NIBBLES, 0, 2), new NodeRef(leafHash, null)));
        nodes.put(new BytesKey(leafHash), new LeafNode(tail(KEY_NIBBLES, 2), VALUE));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).contains(VALUE);
    }

    @Test
    void extension_pathMismatch_provesAbsent() {
        byte[] root = hash(1);
        byte[] leafHash = hash(2);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        // extension path {15,15} cannot prefix KEY's nibbles (KEY starts with 0x1...).
        nodes.put(new BytesKey(root), new ExtensionNode(new int[] {15, 15}, new NodeRef(leafHash, null)));
        nodes.put(new BytesKey(leafHash), new LeafNode(tail(KEY_NIBBLES, 2), VALUE));

        assertThat(new MerklePatriciaTrie(root, nodes).provenValue(KEY)).isEmpty();
    }

    @Test
    void multiLevelTrie_extensionThenBranchToLeaves_resolvesMultipleKeys() {
        // root extension (shared nibble 1) → branch (diverges at nibble 2/3) → two leaves.
        byte[] keyA = new byte[32];
        keyA[0] = 0x12; // nibbles [1,2,0,...]
        byte[] keyB = new byte[32];
        keyB[0] = 0x13; // nibbles [1,3,0,...]
        byte[] valueA = bytes(5, 0xA0);
        byte[] valueB = bytes(9, 0xB0);
        int[] nibblesA = ProofBytes.toNibbles(keyA);
        int[] nibblesB = ProofBytes.toNibbles(keyB);

        byte[] root = hash(1);
        byte[] branchHash = hash(2);
        byte[] leafAHash = hash(3);
        byte[] leafBHash = hash(4);
        NodeRef[] children = new NodeRef[16];
        children[nibblesA[1]] = new NodeRef(leafAHash, null); // slot 2
        children[nibblesB[1]] = new NodeRef(leafBHash, null); // slot 3

        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new ExtensionNode(new int[] {nibblesA[0]}, new NodeRef(branchHash, null)));
        nodes.put(new BytesKey(branchHash), new BranchNode(children, null));
        nodes.put(new BytesKey(leafAHash), new LeafNode(tail(nibblesA, 2), valueA));
        nodes.put(new BytesKey(leafBHash), new LeafNode(tail(nibblesB, 2), valueB));

        MerklePatriciaTrie trie = new MerklePatriciaTrie(root, nodes);
        assertThat(trie.provenValue(keyA)).contains(valueA);
        assertThat(trie.provenValue(keyB)).contains(valueB);
        // a key sharing the prefix but landing on an empty branch slot is proven absent.
        byte[] keyC = new byte[32];
        keyC[0] = 0x14; // nibble[1] = 4 → unpopulated slot
        assertThat(trie.provenValue(keyC)).isEmpty();
    }

    @Test
    void missingChildNode_throws() {
        byte[] root = hash(1);
        NodeRef[] children = new NodeRef[16];
        children[KEY_NIBBLES[0]] = new NodeRef(hash(99), null); // referenced node not in the map
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new BranchNode(children, null));

        assertThatThrownBy(() -> new MerklePatriciaTrie(root, nodes).provenValue(KEY))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("missing proof node");
    }

    @Test
    void cyclicReference_hitsSanityGuard() {
        // a zero-length extension pointing back to itself never consumes path → must be bounded.
        byte[] root = hash(1);
        Map<BytesKey, TrieNode> nodes = new HashMap<>();
        nodes.put(new BytesKey(root), new ExtensionNode(new int[0], new NodeRef(root, null)));

        assertThatThrownBy(() -> new MerklePatriciaTrie(root, nodes).provenValue(KEY))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("sanity limit");
    }

    @Test
    void provenValue_wrongKeyLength_throwsIllegalArgument() {
        MerklePatriciaTrie trie = new MerklePatriciaTrie(EMPTY_TRIE_ROOT, new HashMap<>());
        assertThatThrownBy(() -> trie.provenValue(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("treePath must be 32 bytes");
    }

    // ── helpers ──

    private static int[] tail(int[] nibbles, int from) {
        return Arrays.copyOfRange(nibbles, from, nibbles.length);
    }

    private static byte[] key(int seed) {
        return bytes(32, seed);
    }

    /**
     * Computes a deterministic byte array of length 32 based on the given seed.
     *
     * @param seed the seed value used to influence the deterministic generation of the byte array
     * @return a byte array of length 32 generated using the given seed
     */
    private static byte[] hash(int seed) {
        return bytes(32, 0x40 + seed);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
