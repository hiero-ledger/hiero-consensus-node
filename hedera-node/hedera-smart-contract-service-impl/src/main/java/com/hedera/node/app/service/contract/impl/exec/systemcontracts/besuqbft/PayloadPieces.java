// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

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

    static BlockHeader decodeBlockHeader(final List<RlpDecoder.RlpItem> items) {
        // Pre-London headers are 15 fields; later hard forks (London, Shanghai, Cancun) tack on
        // optional fields. The verifier only needs the 15 always-present fields plus the
        // (optional) state root, so we tolerate any size in [15, 18] here.
        if (items.size() < 15 || items.size() > 18) {
            throw new IllegalArgumentException(
                    "QbftProofPayload: block header has " + items.size() + " fields (expected 15..18)");
        }
        return new BlockHeader(
                Bytes.wrap(items.get(0).bytes()),
                Bytes.wrap(items.get(1).bytes()),
                Bytes.wrap(items.get(2).bytes()),
                Bytes.wrap(items.get(3).bytes()),
                Bytes.wrap(items.get(4).bytes()),
                Bytes.wrap(items.get(5).bytes()),
                Bytes.wrap(items.get(6).bytes()),
                toBigInteger(items.get(7).bytes()),
                toBigInteger(items.get(8).bytes()),
                toBigInteger(items.get(9).bytes()),
                toBigInteger(items.get(10).bytes()),
                toBigInteger(items.get(11).bytes()),
                Bytes.wrap(items.get(12).bytes()),
                Bytes.wrap(items.get(13).bytes()),
                Bytes.wrap(items.get(14).bytes()));
    }

    public record StorageProofEntry(
            @NonNull Bytes key, @NonNull List<Bytes> proof) {}

    static List<StorageProofEntry> decodeStorageProof(final List<RlpDecoder.RlpItem> entries) {
        final List<StorageProofEntry> out = new ArrayList<>(entries.size());
        for (final var entry : entries) {
            final var fields = entry.list();
            if (fields.size() != 2) {
                throw new IllegalArgumentException(
                        "QbftProofPayload: storage-proof entry has " + fields.size() + " fields (expected 2)");
            }
            out.add(new StorageProofEntry(
                    Bytes.wrap(fields.get(0).bytes()),
                    decodeBytesList(fields.get(1).list())));
        }
        return List.copyOf(out);
    }

    static List<Bytes> decodeBytesList(final List<RlpDecoder.RlpItem> items) {
        final List<Bytes> out = new ArrayList<>(items.size());
        for (final var item : items) {
            out.add(Bytes.wrap(item.bytes()));
        }
        return List.copyOf(out);
    }

    private static BigInteger toBigInteger(final byte[] raw) {
        return raw.length == 0 ? BigInteger.ZERO : new BigInteger(1, raw);
    }
}
