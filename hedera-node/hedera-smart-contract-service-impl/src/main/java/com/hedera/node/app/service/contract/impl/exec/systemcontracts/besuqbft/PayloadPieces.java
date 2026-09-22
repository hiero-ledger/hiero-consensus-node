// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

import com.esaulpaugh.headlong.rlp.RLPDecoder;
import com.esaulpaugh.headlong.rlp.RLPItem;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class PayloadPieces {
    /**
     * Minimal mirror of {@code QbftBundleConstructor.BlockHeader}. Stores the raw RLP item list
     * of the header so the verifier can recompute the block hash without committing to a specific
     * hard-fork field set (post-London / Shanghai / Cancun fields are optional in the encoder).
     */
    public record BlockHeader(
            @NonNull Bytes parentHash,
            @NonNull Bytes sha3Uncles,
            @NonNull Bytes miner,
            @NonNull Bytes stateRoot,
            @NonNull Bytes transactionsRoot,
            @NonNull Bytes receiptsRoot,
            @NonNull Bytes logsBloom,
            @NonNull BigInteger difficulty,
            @NonNull BigInteger number,
            @NonNull BigInteger gasLimit,
            @NonNull BigInteger gasUsed,
            @NonNull BigInteger timestamp,
            @NonNull Bytes extraData,
            @NonNull Bytes mixHash,
            @NonNull Bytes nonce) {}

    static BlockHeader decodeBlockHeader(final List<RLPItem> items) {
        // Pre-London headers are 15 fields; later hard forks (London, Shanghai, Cancun) tack on
        // optional fields. The verifier only needs the 15 always-present fields plus the
        // (optional) state root, so we tolerate any size in [15, 18] here.
        if (items.size() < 15 || items.size() > 18) {
            throw new IllegalArgumentException(
                    "QbftProofPayload: block header has " + items.size() + " fields (expected 15..18)");
        }
        // Every header field, including optional hard-fork fields, must be an RLP string.
        final var fields = decodeBytesList(items);
        return new BlockHeader(
                fields.get(0),
                fields.get(1),
                fields.get(2),
                fields.get(3),
                fields.get(4),
                fields.get(5),
                fields.get(6),
                toBigInteger(fields.get(7).toByteArray()),
                toBigInteger(fields.get(8).toByteArray()),
                toBigInteger(fields.get(9).toByteArray()),
                toBigInteger(fields.get(10).toByteArray()),
                toBigInteger(fields.get(11).toByteArray()),
                fields.get(12),
                fields.get(13),
                fields.get(14));
    }

    public record StorageProofEntry(
            @NonNull Bytes key, @NonNull List<Bytes> proof) {}

    static List<StorageProofEntry> decodeStorageProof(final List<RLPItem> entries) {
        final List<StorageProofEntry> out = new ArrayList<>(entries.size());
        for (final var entry : entries) {
            final var fields = decodeList(entry);
            if (fields.size() != 2) {
                throw new IllegalArgumentException(
                        "QbftProofPayload: storage-proof entry has " + fields.size() + " fields (expected 2)");
            }
            out.add(new StorageProofEntry(
                    Bytes.wrap(decodeBytes(fields.get(0))), decodeBytesList(decodeList(fields.get(1)))));
        }
        return List.copyOf(out);
    }

    static List<Bytes> decodeBytesList(final List<RLPItem> items) {
        final List<Bytes> out = new ArrayList<>(items.size());
        for (final var item : items) {
            out.add(Bytes.wrap(decodeBytes(item)));
        }
        return List.copyOf(out);
    }

    /** Decodes exactly one top-level list; Headlong otherwise permits trailing items in the buffer. */
    static List<RLPItem> decodePayloadList(final byte[] encoded) {
        if (encoded.length == 0) {
            throw new IllegalArgumentException("RLP: empty payload");
        }
        final var item = RLPDecoder.RLP_STRICT.wrapItem(encoded);
        if (item.endIndex != encoded.length) {
            throw new IllegalArgumentException("RLP: trailing bytes after top-level item");
        }
        return decodeList(item);
    }

    static List<RLPItem> decodeList(final RLPItem item) {
        if (!item.isList()) {
            throw new IllegalArgumentException("expected RLP list, got bytes");
        }
        return item.asRLPList().elements(RLPDecoder.RLP_STRICT);
    }

    static byte[] decodeBytes(final RLPItem item) {
        // RLPItem.asBytes() also accepts lists; proof fields must retain their string/list distinction.
        if (!item.isString()) {
            throw new IllegalArgumentException("expected RLP bytes, got list");
        }
        return item.asBytes();
    }

    private static BigInteger toBigInteger(final byte[] raw) {
        return raw.length == 0 ? BigInteger.ZERO : new BigInteger(1, raw);
    }
}
