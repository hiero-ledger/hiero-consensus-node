// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.keccak256;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.leftPad32;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.BranchNode;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.ExtensionNode;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.LeafNode;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.NodeRef;
import com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie.TrieNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class for decoding data in the Recursive Length Prefix (RLP) format, primarily used in
 * EVM related structures such as Merkle Patricia Trie and accounts.
 * This class provides several methods to parse and convert RLP-encoded data into strongly-typed
 * objects relevant in Ethereum's data model.
 *
 * <p>This class is final and cannot be instantiated.
 */
public final class RlpDecoder {

    private static final String SOURCE = "RlpDecoder";

    private RlpDecoder() {}

    /**
     * Decodes {@code proofNodes} into a {@link MerklePatriciaTrie} rooted at {@code rootHash32}:
     * each node is parsed into a typed {@link TrieNode} and indexed by its keccak hash.
     */
    @NonNull
    public static MerklePatriciaTrie decodeMerklePatriciaTrie(
            @NonNull final byte[] rootHash32, @NonNull final byte[][] proofNodes) {
        final Map<BytesKey, TrieNode> nodesByHash = new HashMap<>();
        for (final byte[] raw : proofNodes) {
            if (raw.length == 0) {
                throw new ProofException(SOURCE, "empty proof node");
            }
            nodesByHash.put(new BytesKey(keccak256(raw)), decodeNode(Rlp.decodeOne(raw)));
        }
        return new MerklePatriciaTrie(rootHash32, nodesByHash);
    }

    /** Decodes an RLP {@code [nonce, balance, storageRoot, codeHash]} account value. */
    @NonNull
    public static EvmAccount decodeAccount(@NonNull final byte[] accountRlp) {
        final Rlp.Item item = Rlp.decodeOne(accountRlp);
        if (!item.isList() || item.children().size() != 4) {
            throw new ProofException(SOURCE, "account value is not RLP [nonce, balance, storageRoot, codeHash]");
        }
        return new EvmAccount(
                leftPad32(item.children().get(2).asBytes(), "account.storageRoot"),
                leftPad32(item.children().get(3).asBytes(), "account.codeHash"));
    }

    /** Parses one Merkle-Patricia trie node: a 17-item branch or a 2-item short node (leaf or extension). */
    @NonNull
    private static TrieNode decodeNode(@NonNull final Rlp.Item node) {
        if (!node.isList()) {
            throw new ProofException(SOURCE, "MPT node is not an RLP list");
        }
        final List<Rlp.Item> items = node.children();
        if (items.size() == 17) {
            final NodeRef[] children = new NodeRef[16];
            for (int i = 0; i < 16; i++) {
                children[i] = childRef(items.get(i));
            }
            final Rlp.Item valueItem = items.get(16);
            return new BranchNode(children, valueItem.isEmptyString() ? null : valueItem.asBytes());
        }
        if (items.size() == 2) {
            final HexPrefix hp = decodeHexPrefix(items.get(0).asBytes());
            if (hp.leaf()) {
                return new LeafNode(hp.path(), items.get(1).asBytes());
            }
            final NodeRef child = childRef(items.get(1));
            if (child == null) {
                throw new ProofException(SOURCE, "extension node with empty child");
            }
            return new ExtensionNode(hp.path(), child);
        }
        throw new ProofException(SOURCE, "invalid MPT node length " + items.size());
    }

    /**
     * Decodes a child slot into a {@link NodeRef}: an inlined sub-node (an embedded RLP list, or a
     * sub-32-byte encoding), a 32-byte hash reference, or {@code null} for an empty slot.
     */
    @Nullable
    private static NodeRef childRef(@NonNull final Rlp.Item item) {
        if (item.isList()) {
            return new NodeRef(null, decodeNode(item));
        }
        final byte[] ref = item.asBytes();
        if (ref.length == 0) {
            return null;
        }
        if (ref.length == 32) {
            return new NodeRef(ref, null);
        }
        if (ref.length < 32) {
            return new NodeRef(null, decodeNode(Rlp.decodeOne(ref)));
        }
        throw new ProofException(SOURCE, "invalid MPT node reference length " + ref.length);
    }

    private static HexPrefix decodeHexPrefix(final byte[] encoded) {
        final int[] all = ProofBytes.toNibbles(encoded);
        if (all.length == 0) {
            throw new ProofException(SOURCE, "empty hex-prefix path");
        }
        final int flags = all[0];
        final boolean leaf = (flags & 0x2) != 0;
        final boolean odd = (flags & 0x1) != 0;
        final int start = odd ? 1 : 2;
        if (!odd && all.length > 1 && all[1] != 0) {
            throw new ProofException(SOURCE, "invalid even hex-prefix path");
        }
        return new HexPrefix(leaf, Arrays.copyOfRange(all, start, all.length));
    }

    private record HexPrefix(boolean leaf, int[] path) {}
}
