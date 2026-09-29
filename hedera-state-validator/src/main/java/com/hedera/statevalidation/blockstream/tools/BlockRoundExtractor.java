// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Splits the items of one round in a block into logical transactions, and collects the round's state changes.
 * Shared by {@link OutputRecordCompare} and {@link TxDump}.
 *
 * <p>Grouping rules:
 * <ul>
 *   <li>A {@code SIGNED_TRANSACTION} starts a new transaction record.</li>
 *   <li>The first {@code TRANSACTION_RESULT} after it belongs to that record. Every further
 *       {@code TRANSACTION_RESULT} without a new {@code SIGNED_TRANSACTION} starts a new record of its own. This
 *       is how inner transactions of an atomic batch appear; they are labelled with the inner transaction's ID,
 *       taken from the batch body by index.</li>
 *   <li>{@code TRANSACTION_OUTPUT} and {@code STATE_CHANGES} items belong to the most recent record.</li>
 *   <li>Every {@code STATE_CHANGES} item in the round is also collected into a round-level list, including those
 *       that appear before the first transaction.</li>
 * </ul>
 */
public final class BlockRoundExtractor {

    /** Largest transaction accepted when parsing, matching the block reader and nodeTransaction.maxBytes (32 MiB). */
    public static final int MAX_TRANSACTION_BYTES = 32 * 1024 * 1024;

    /** One logical transaction. {@code note} is empty for top-level transactions. */
    public record TxRecord(
            String txId,
            String note,
            TransactionBody body,
            TransactionResult result,
            List<TransactionOutput> outputs,
            List<StateChange> stateChanges) {

        /** txId plus the note, for reports. */
        public String label() {
            return note.isEmpty() ? txId : txId + " [" + note + "]";
        }
    }

    /**
     * Content of one round.
     *
     * @param present whether the block contains this round at all
     * @param transactions logical transactions in stream order
     * @param allStateChanges every state change in the round, in stream order
     * @param unattributedStateChanges state changes that appeared before the first transaction of the round
     */
    public record RoundContent(
            long round,
            boolean present,
            List<TxRecord> transactions,
            List<StateChange> allStateChanges,
            List<StateChange> unattributedStateChanges) {}

    private BlockRoundExtractor() {}

    /** Round numbers in the block, in stream order, limited to [fromRound, toRound]. */
    public static List<Long> roundsIn(final Block block, final long fromRound, final long toRound) {
        final List<Long> rounds = new ArrayList<>();
        for (final BlockItem item : block.items()) {
            if (item.hasRoundHeader()) {
                final long rd = item.roundHeader().roundNumber();
                if (rd >= fromRound && rd <= toRound) {
                    rounds.add(rd);
                }
            }
        }
        return rounds;
    }

    /** Extracts the logical transactions and state changes of {@code round} from {@code block}. */
    public static RoundContent extract(final Block block, final long round) {
        final List<TxRecord> transactions = new ArrayList<>();
        final List<StateChange> all = new ArrayList<>();
        final List<StateChange> unattributed = new ArrayList<>();

        boolean inRound = false;
        boolean present = false;
        Builder current = null;
        String parentTxId = null;
        List<Bytes> batchInner = List.of();
        int innerIndex = 0;

        for (final BlockItem item : block.items()) {
            if (item.hasRoundHeader()) {
                if (inRound && current != null) {
                    transactions.add(current.build());
                }
                current = null;
                parentTxId = null;
                batchInner = List.of();
                innerIndex = 0;
                inRound = item.roundHeader().roundNumber() == round;
                present |= inRound;
                continue;
            }
            if (!inRound) {
                continue;
            }
            switch (item.item().kind()) {
                case SIGNED_TRANSACTION -> {
                    if (current != null) {
                        transactions.add(current.build());
                    }
                    final TransactionBody body = parseBody(item.item().as());
                    final String txId = txIdString(body);
                    current = new Builder(txId, "", body);
                    parentTxId = txId;
                    batchInner = body != null && body.hasAtomicBatch()
                            ? body.atomicBatchOrThrow().transactions()
                            : List.of();
                    innerIndex = 0;
                }
                case TRANSACTION_RESULT -> {
                    final TransactionResult result = item.transactionResult();
                    if (current == null) {
                        current = new Builder("<no-transaction>", "result without a preceding transaction", null);
                        current.result = result;
                    } else if (current.result == null) {
                        current.result = result;
                    } else {
                        // A further result without a new SIGNED_TRANSACTION: an atomic batch inner transaction.
                        transactions.add(current.build());
                        final TransactionBody innerBody =
                                innerIndex < batchInner.size() ? parseBody(batchInner.get(innerIndex)) : null;
                        current = new Builder(
                                txIdString(innerBody), "batch inner #" + innerIndex + " of " + parentTxId, innerBody);
                        current.result = result;
                        innerIndex++;
                    }
                }
                case TRANSACTION_OUTPUT -> {
                    if (current != null && item.transactionOutput() != null) {
                        current.outputs.add(item.transactionOutput());
                    }
                }
                case STATE_CHANGES -> {
                    final List<StateChange> changes = item.stateChangesOrThrow().stateChanges();
                    all.addAll(changes);
                    if (current != null) {
                        current.stateChanges.addAll(changes);
                    } else {
                        unattributed.addAll(changes);
                    }
                }
                default -> {
                    // event headers, trace data, block header/footer/proof: not part of the comparison
                }
            }
        }
        if (inRound && current != null) {
            transactions.add(current.build());
        }
        return new RoundContent(round, present, transactions, all, unattributed);
    }

    /**
     * Parses a serialized {@code SignedTransaction} into its body, allowing transactions up to
     * {@link #MAX_TRANSACTION_BYTES}. The PBJ convenience overloads stop at 2 MiB, which rejects valid node
     * transactions. Returns {@code null} if the bytes cannot be parsed.
     */
    public static TransactionBody parseBody(final Bytes signedTransactionBytes) {
        try {
            final SignedTransaction st = SignedTransaction.PROTOBUF.parse(
                    signedTransactionBytes.toReadableSequentialData(),
                    false,
                    false,
                    Codec.DEFAULT_MAX_DEPTH,
                    MAX_TRANSACTION_BYTES);
            return TransactionBody.PROTOBUF.parse(
                    st.bodyBytes().toReadableSequentialData(),
                    false,
                    false,
                    Codec.DEFAULT_MAX_DEPTH,
                    MAX_TRANSACTION_BYTES);
        } catch (final Exception e) {
            return null;
        }
    }

    /** Formats a transaction ID as {@code account@seconds.nanos[.nN][.sched]}. */
    public static String txIdString(final TransactionBody body) {
        if (body == null) {
            return "<unparseable>";
        }
        final TransactionID id = body.transactionID();
        if (id == null) {
            return "<no-id>";
        }
        final var acct = id.accountID();
        final long num = acct == null ? -1 : acct.accountNumOrElse(-1L);
        final var start = id.transactionValidStart();
        final long sec = start == null ? -1 : start.seconds();
        final int nanos = start == null ? -1 : start.nanos();
        return num + "@" + sec + "." + nanos
                + (id.nonce() != 0 ? ".n" + id.nonce() : "")
                + (id.scheduled() ? ".sched" : "");
    }

    /** Block files under {@code dir}, keyed by block number. */
    public static Map<Long, Path> indexByBlockNumber(final Path dir) throws IOException {
        final Map<Long, Path> map = new TreeMap<>();
        try (final Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> !Files.isDirectory(p))
                    .filter(p -> BlockStreamAccess.isBlockFile(p, false))
                    .forEach(p -> {
                        final long n = BlockStreamAccess.extractBlockNumber(p);
                        if (n != -1) {
                            map.put(n, p);
                        }
                    });
        }
        return map;
    }

    /** The block file under {@code dir} that contains {@code round}, or {@code null}. */
    public static Path findBlockContainingRound(final Path dir, final long round) throws IOException {
        for (final Path p : indexByBlockNumber(dir).values()) {
            final Block b = BlockStreamAccess.blockFrom(p);
            for (final BlockItem item : b.items()) {
                if (item.hasRoundHeader() && item.roundHeader().roundNumber() == round) {
                    return p;
                }
            }
        }
        return null;
    }

    private static final class Builder {
        private final String txId;
        private final String note;
        private final TransactionBody body;
        private TransactionResult result;
        private final List<TransactionOutput> outputs = new ArrayList<>();
        private final List<StateChange> stateChanges = new ArrayList<>();

        private Builder(final String txId, final String note, final TransactionBody body) {
            this.txId = txId;
            this.note = note;
            this.body = body;
        }

        private TxRecord build() {
            return new TxRecord(txId, note, body, result, List.copyOf(outputs), List.copyOf(stateChanges));
        }
    }
}
