// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * In-memory view of the RLP-encoded QBFT config payload produced by the EVM-side relay.
 *
 * <p>Top-level RLP layout (5 items):
 * <pre>
 *   [ genesisBlockHeader,  // RLP list — extraData carries the initial QBFT validator set
 *     currentBlockHeader,  // RLP list — QBFT committed seal must recover to genesis validator
 *     ledgerConfiguration, // RLP bytes — protobuf-serialized ClprLedgerConfiguration
 *     accountProof,        // RLP list of MPT trie nodes
 *     storageProof ]       // RLP list of [key, proof[]] entries (at least 1)
 * </pre>
 */
public record QbftLedgerConfigPayload(
        @NonNull PayloadPieces.BlockHeader genesisBlockHeader,
        @NonNull PayloadPieces.BlockHeader currentBlockHeader,
        @NonNull ClprLedgerConfiguration ledgerConfiguration,
        @NonNull List<Bytes> clprServiceAccountProof,
        @NonNull List<PayloadPieces.StorageProofEntry> clprServiceStorageProofs) {

    /**
     * Decode a top-level RLP-encoded QBFT config payload.
     */
    @NonNull
    public static QbftLedgerConfigPayload decode(@NonNull final byte[] rlp) {
        final var top = RlpDecoder.decode(rlp).list();
        if (top.size() != 5) {
            throw new IllegalArgumentException(
                    "QbftLedgerConfigPayload: expected 5 top-level RLP items, got " + top.size());
        }
        final var genesisBlockHeader =
                PayloadPieces.decodeBlockHeader(top.get(0).list());
        final var currentBlockHeader =
                PayloadPieces.decodeBlockHeader(top.get(1).list());
        final ClprLedgerConfiguration ledgerConfiguration;
        try {
            ledgerConfiguration = ClprLedgerConfiguration.PROTOBUF.parse(
                    Bytes.wrap(top.get(2).bytes()).toReadableSequentialData());
        } catch (final Exception e) {
            throw new IllegalArgumentException(
                    "QbftLedgerConfigPayload: item 2 is not a valid ClprLedgerConfiguration", e);
        }
        final var accountProof = PayloadPieces.decodeBytesList(top.get(3).list());
        final var storageProof = PayloadPieces.decodeStorageProof(top.get(4).list());
        return new QbftLedgerConfigPayload(
                genesisBlockHeader, currentBlockHeader, ledgerConfiguration, accountProof, storageProof);
    }
}
