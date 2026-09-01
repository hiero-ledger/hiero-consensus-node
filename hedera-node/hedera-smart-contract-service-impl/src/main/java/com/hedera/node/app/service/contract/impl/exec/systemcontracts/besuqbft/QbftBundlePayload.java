// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * In-memory view of the RLP-encoded QBFT proof payload produced by the EVM-side relay
 * ({@code org.hiero.clpr.relay.evm.QbftBundleConstructor.QbftBundlePayload} in the
 * {@code clpr-evm-endpoint} repo).
 *
 * <p>Top-level RLP layout (5 items):
 * <pre>
 *   [ blockHeader,            // RLP list of header fields (variable count by hard fork)
 *     accountProof,           // RLP list: MPT account-proof trie nodes (each item is bytes)
 *     storageProof,           // RLP list: each entry is [key, value, proofNodes[]]
 *     innerContentBytes ]     // RLP bytes: protobuf-serialized ClprBundleContent
 *                             //            (or ClprLedgerConfiguration for verifyConfig)
 * </pre>
 *
 * <p>The same payload shape is reused for both {@code verifyBundle} and {@code verifyConfig};
 * only the protobuf type of the inner bytes differs. The shape itself does not commit to a
 * specific inner type — callers parse {@link #innerContentBytes()} as whatever they expect.
 */
public record QbftBundlePayload(
        @NonNull PayloadPieces.BlockHeader blockHeader,
        @NonNull List<Bytes> accountProof,
        @NonNull List<PayloadPieces.StorageProofEntry> storageProof,
        @NonNull Bytes innerContentBytes) {

    /**
     * Decode a top-level RLP-encoded QBFT proof payload. Throws {@link IllegalArgumentException}
     * if the bytes are malformed or fail to match the expected 4-element shape.
     */
    @NonNull
    public static QbftBundlePayload decode(@NonNull final byte[] rlp) {
        final var top = RlpDecoder.decode(rlp).list();
        if (top.size() != 4) {
            throw new IllegalArgumentException("QbftProofPayload: expected 4 top-level RLP items, got " + top.size());
        }
        final var blockHeader = PayloadPieces.decodeBlockHeader(top.get(0).list());
        final var accountProof = PayloadPieces.decodeBytesList(top.get(1).list());
        final var storageProof = PayloadPieces.decodeStorageProof(top.get(2).list());
        final var innerContentBytes = Bytes.wrap(top.get(3).bytes());
        return new QbftBundlePayload(blockHeader, accountProof, storageProof, innerContentBytes);
    }
}
