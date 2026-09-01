// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.requireLength;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * A partial Ethereum Merkle Patricia Trie reconstructed from proof nodes, rooted at a trusted hash.
 * This type is pure data and traversal.
 */
public final class MerklePatriciaTrie {

    private static final String SOURCE = "MerklePatriciaTrie";

    /**
     * Upper bound on node visits in a single {@link #provenValue} traversal. A genuine proof walks
     * at most ~64 nibble levels; anything beyond this signals a malformed or cyclic proof.
     */
    private static final int MAX_TRAVERSAL_STEPS = 128;

    /** {@code keccak256(RLP(empty string)) = keccak256(0x80)} — the empty MPT root. */
    private static final byte[] EMPTY_TRIE_ROOT =
            HexFormat.of().parseHex("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421");

    private final byte[] rootHash32;
    private final Map<BytesKey, TrieNode> nodesByHash;

    MerklePatriciaTrie(@NonNull final byte[] rootHash32, @NonNull final Map<BytesKey, TrieNode> nodesByHash) {
        this.rootHash32 = checkedCopy(rootHash32, 32, "rootHash32");
        this.nodesByHash = nodesByHash;
    }

    /**
     *  {@link #provenValue} answers "what value does this trie prove for the given key?":
     *  <ul>
     *      <li>a present value → {@code Optional.of(value)},</li>
     *      <li>a valid exclusion proof (the key is proven absent) → {@code Optional.empty()},</li>
     *      <li>a malformed proof (a referenced node is missing, or traversal runs away) → throws
     *         {@link ProofException}.</li>
     *  </ul>
     *
     * Returns the value this trie proves for {@code treePath}, {@link Optional#empty()} if the key is
     * proven absent, or throws {@link ProofException} if the proof is malformed.
     */
    @NonNull
    public Optional<byte[]> provenValue(@NonNull final byte[] treePath) {
        requireLength(treePath, 32, "treePath");
        if (nodesByHash.isEmpty() && Arrays.equals(rootHash32, EMPTY_TRIE_ROOT)) {
            return Optional.empty();
        }
        final int[] path = ProofBytes.toNibbles(treePath);
        int pathPos = 0;
        TrieNode node = resolveHash(rootHash32);
        int guard = 0;
        while (true) {
            if (++guard > MAX_TRAVERSAL_STEPS) {
                throw new ProofException(SOURCE, "MPT traversal exceeded sanity limit");
            }
            switch (node) {
                case BranchNode branch -> {
                    if (pathPos == path.length) {
                        return branch.value() == null
                                ? Optional.empty()
                                : Optional.of(branch.value().clone());
                    }
                    final NodeRef ref = branch.children()[path[pathPos]];
                    if (ref == null) {
                        return Optional.empty();
                    }
                    pathPos++;
                    node = resolve(ref);
                }
                case LeafNode leaf -> {
                    if (!startsWith(path, pathPos, leaf.path())) {
                        return Optional.empty();
                    }
                    pathPos += leaf.path().length;
                    return pathPos == path.length ? Optional.of(leaf.value().clone()) : Optional.empty();
                }
                case ExtensionNode extension -> {
                    if (!startsWith(path, pathPos, extension.path())) {
                        return Optional.empty();
                    }
                    pathPos += extension.path().length;
                    node = resolve(extension.child());
                }
            }
        }
    }

    /** Resolves a child reference: an inlined node directly, or a hash looked up in the node map. */
    @NonNull
    private TrieNode resolve(@NonNull final NodeRef ref) {
        return ref.inline() != null ? ref.inline() : resolveHash(ref.hash());
    }

    @NonNull
    private TrieNode resolveHash(@NonNull final byte[] hash32) {
        final TrieNode node = nodesByHash.get(new BytesKey(hash32));
        if (node == null) {
            throw new ProofException(SOURCE, "missing proof node for hash reference");
        }
        return node;
    }

    private static boolean startsWith(final int[] path, final int offset, final int[] segment) {
        if (offset + segment.length > path.length) {
            return false;
        }
        for (int i = 0; i < segment.length; i++) {
            if (path[offset + i] != segment[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A decoded Merkle Patricia Trie node: a 17-item {@link BranchNode}, a {@link LeafNode}, or an
     * {@link ExtensionNode} (the two 2-item forms).
     */
    sealed interface TrieNode permits BranchNode, LeafNode, ExtensionNode {}

    /**
     * A 17-item branch: 16 child slots indexed by nibble (a {@code null} slot is empty) plus an
     * optional value at the node itself ({@code null} when the branch carries no value).
     */
    record BranchNode(@NonNull NodeRef[] children, @Nullable byte[] value) implements TrieNode {}

    /** A leaf: {@code path} is the remaining key nibbles and {@code value} the stored value. */
    record LeafNode(@NonNull int[] path, @NonNull byte[] value) implements TrieNode {}

    /**
     * An extension: {@code path} is a shared nibble segment and {@code child} continues the lookup.
     * A real extension always references a child; the decoder rejects an empty reference rather than
     * constructing one here.
     */
    record ExtensionNode(@NonNull int[] path, @NonNull NodeRef child) implements TrieNode {}

    /**
     * A child reference: either a 32-byte {@code hash} resolved against the node map, or an
     * {@code inline} node embedded in the parent. Exactly one is non-null.
     */
    record NodeRef(@Nullable byte[] hash, @Nullable TrieNode inline) {}
}
