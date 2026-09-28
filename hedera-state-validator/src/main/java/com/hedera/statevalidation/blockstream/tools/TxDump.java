// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/// Prints the full block-stream content for one or more transactions identified by txId within a block
/// file or a block directory. For each matching transaction it prints: the parsed TransactionBody, the
/// TransactionResult, any TransactionOutput(s), and any StateChanges attributed to it.
/// Transactions are matched by position within a round (--pos) or by txId string (--tx). When matching
/// by txId, ALL occurrences are printed (handles the 10@0.0 synthetic-ID collision — each is labeled
/// with its position).
/// Usage:
///   java -cp ./hedera-state-validator-<ver>.jar com.hedera.statevalidation.blockstream.tools.TxDump \
///        --blocks <dir-or-file> --round <n> --tx <txid>
///   java -cp ./hedera-state-validator-0.74.jar com.hedera.statevalidation.blockstream.tools.TxDump \
///        --blocks <dir-or-file> --round <n> --pos <position>
///   java -cp ./hedera-state-validator-0.74.jar com.hedera.statevalidation.blockstream.tools.TxDump \
///        --blocks <dir-or-file> --round <n>   (prints all transactions, summary
// only)</n></dir-or-file></position></n></dir-or-file></txid></n></dir-or-file></ver>
public final class TxDump {

    public static void main(final String[] args) throws Exception {
        Path blocksPath = null;
        long round = Long.MIN_VALUE;
        String txFilter = null;
        int posFilter = -1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--blocks" -> blocksPath = Path.of(args[++i]);
                case "--round" -> round = Long.parseLong(args[++i]);
                case "--tx" -> txFilter = args[++i];
                case "--pos" -> posFilter = Integer.parseInt(args[++i]);
                default -> {}
            }
        }
        if (blocksPath == null || round == Long.MIN_VALUE) {
            System.err.println("Required: --blocks <dir-or-file> --round <N> [--tx <txId> | --pos <N>]");
            System.exit(2);
        }

        final Path file = Files.isDirectory(blocksPath) ? findBlockContainingRound(blocksPath, round) : blocksPath;
        if (file == null) {
            System.err.printf("Round %d not found in %s%n", round, blocksPath);
            System.exit(1);
        }

        final Block block = BlockStreamAccess.blockFrom(file);
        final List<TxContent> txns = extractTransactions(block, round);
        System.out.printf("Round %d — %d transaction(s) in %s%n%n", round, txns.size(), file.getFileName());

        if (txFilter == null && posFilter == -1) {
            // Summary mode: list all transactions
            for (int i = 0; i < txns.size(); i++) {
                final TxContent t = txns.get(i);
                final String status = t.result != null ? statusOf(t.result) : "?";
                System.out.printf(
                        "  pos=%3d  txId=%-36s  status=%-30s  outputs=%d  stateChanges=%d%n",
                        i, t.txId, status, t.outputs.size(), t.stateChanges.size());
            }
            return;
        }

        // Detail mode: print full content for matching transaction(s)
        int matches = 0;
        for (int i = 0; i < txns.size(); i++) {
            final TxContent t = txns.get(i);
            final boolean match = (posFilter >= 0 && i == posFilter) || (txFilter != null && txFilter.equals(t.txId));
            if (!match) {
                continue;
            }
            matches++;
            System.out.printf("======== pos=%d  txId=%s ========%n", i, t.txId);

            System.out.println("-- TRANSACTION BODY --");
            if (t.body != null) {
                System.out.println(t.body);
            } else {
                System.out.println("  (unparseable)");
            }

            System.out.println("-- TRANSACTION RESULT --");
            if (t.result != null) {
                System.out.println(t.result);
            } else {
                System.out.println("  (none)");
            }

            if (!t.outputs.isEmpty()) {
                System.out.println("-- TRANSACTION OUTPUT(S) --");
                for (final Object o : t.outputs) {
                    System.out.println(o);
                }
            }

            if (!t.stateChanges.isEmpty()) {
                System.out.println("-- STATE CHANGES --");
                for (final StateChange sc : t.stateChanges) {
                    final String stateName = safeStateName(sc.stateId());
                    System.out.printf("  [%s (id=%d)] %s%n", stateName, sc.stateId(), sc.changeOperation());
                }
            }
            System.out.println();
        }

        if (matches == 0) {
            System.out.printf(
                    "No transaction matched %s in round %d%n",
                    posFilter >= 0 ? "pos=" + posFilter : "txId=" + txFilter, round);
        } else {
            System.out.printf("%d transaction(s) matched.%n", matches);
        }
    }

    private record TxContent(
            String txId, Object body, Object result, List<Object> outputs, List<StateChange> stateChanges) {}

    /**
     * Walks the block and groups items into per-transaction records for the target round. Items are
     * attributed to the most recently seen SIGNED_TRANSACTION until the next SIGNED_TRANSACTION or
     * ROUND_HEADER.
     */
    private static List<TxContent> extractTransactions(final Block block, final long round) {
        final List<TxContent> out = new ArrayList<>();
        long currentRound = -1;
        boolean inRound = false;

        String txId = null;
        Object body = null;
        Object result = null;
        List<Object> outputs = new ArrayList<>();
        List<StateChange> stateChanges = new ArrayList<>();
        boolean have = false;

        for (final BlockItem item : block.items()) {
            if (item.hasRoundHeader()) {
                if (have && inRound) {
                    out.add(new TxContent(txId, body, result, outputs, stateChanges));
                }
                have = false;
                txId = null;
                body = null;
                result = null;
                outputs = new ArrayList<>();
                stateChanges = new ArrayList<>();
                currentRound = item.roundHeader().roundNumber();
                inRound = currentRound == round;
                continue;
            }
            if (!inRound) {
                continue;
            }
            switch (item.item().kind()) {
                case SIGNED_TRANSACTION -> {
                    if (have) {
                        out.add(new TxContent(txId, body, result, outputs, stateChanges));
                    }
                    final Bytes txBytes = item.item().as();
                    txId = txIdString(txBytes);
                    body = parseBody(txBytes);
                    result = null;
                    outputs = new ArrayList<>();
                    stateChanges = new ArrayList<>();
                    have = true;
                }
                case TRANSACTION_RESULT -> {
                    if (have) {
                        result = item.transactionResult();
                    }
                }
                case TRANSACTION_OUTPUT -> {
                    if (have && item.transactionOutput() != null) {
                        outputs.add(item.transactionOutput());
                    }
                }
                case STATE_CHANGES -> {
                    if (have) {
                        stateChanges.addAll(item.stateChangesOrThrow().stateChanges());
                    }
                }
                default -> {
                    /* ignore event headers, trace, proofs, block header/footer */
                }
            }
        }
        if (have && inRound) {
            out.add(new TxContent(txId, body, result, outputs, stateChanges));
        }
        return out;
    }

    private static String txIdString(final Bytes transactionBytes) {
        try {
            final SignedTransaction st = SignedTransaction.PROTOBUF.parse(transactionBytes.toReadableSequentialData());
            final TransactionBody tb =
                    TransactionBody.PROTOBUF.parse(st.bodyBytes().toReadableSequentialData());
            final TransactionID id = tb.transactionID();
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
        } catch (final Exception e) {
            return "<unparseable>";
        }
    }

    private static Object parseBody(final Bytes transactionBytes) {
        try {
            final SignedTransaction st = SignedTransaction.PROTOBUF.parse(transactionBytes.toReadableSequentialData());
            return TransactionBody.PROTOBUF.parse(st.bodyBytes().toReadableSequentialData());
        } catch (final Exception e) {
            return null;
        }
    }

    private static String statusOf(final Object result) {
        try {
            final var r = (TransactionResult) result;
            return r.status().toString();
        } catch (final Exception e) {
            return "?";
        }
    }

    private static String safeStateName(final int stateId) {
        try {
            return BlockStreamUtils.stateNameOf(stateId);
        } catch (final Exception e) {
            return "state#" + stateId;
        }
    }

    private static Path findBlockContainingRound(final Path dir, final long round) throws IOException {
        final Map<Long, Path> byNum = new TreeMap<>();
        try (final Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> !Files.isDirectory(p))
                    .filter(p -> BlockStreamAccess.isBlockFile(p, false))
                    .forEach(p -> {
                        final long n = BlockStreamAccess.extractBlockNumber(p);
                        if (n != -1) {
                            byNum.put(n, p);
                        }
                    });
        }
        for (final Path p : byNum.values()) {
            final Block b = BlockStreamAccess.blockFrom(p);
            for (final BlockItem item : b.items()) {
                if (item.hasRoundHeader() && item.roundHeader().roundNumber() == round) {
                    return p;
                }
            }
        }
        return null;
    }

    private TxDump() {}
}
