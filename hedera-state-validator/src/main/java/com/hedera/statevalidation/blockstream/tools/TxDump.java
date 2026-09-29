// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.statevalidation.blockstream.tools.BlockRoundExtractor.RoundContent;
import com.hedera.statevalidation.blockstream.tools.BlockRoundExtractor.TxRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        final Path file = Files.isDirectory(blocksPath)
                ? BlockRoundExtractor.findBlockContainingRound(blocksPath, round)
                : blocksPath;
        if (file == null) {
            System.err.printf("Round %d not found in %s%n", round, blocksPath);
            System.exit(1);
        }

        final Block block = BlockStreamAccess.blockFrom(file);
        final RoundContent content = BlockRoundExtractor.extract(block, round);
        if (!content.present()) {
            System.err.printf("Round %d not found in %s%n", round, file);
            System.exit(1);
        }
        final List<TxRecord> txns = content.transactions();
        System.out.printf("Round %d — %d transaction(s) in %s%n%n", round, txns.size(), file.getFileName());
        if (!content.unattributedStateChanges().isEmpty()) {
            System.out.printf(
                    "  %d state change(s) before the first transaction%n%n",
                    content.unattributedStateChanges().size());
        }

        if (txFilter == null && posFilter == -1) {
            for (int i = 0; i < txns.size(); i++) {
                final TxRecord t = txns.get(i);
                final String status = t.result() != null ? t.result().status().toString() : "?";
                System.out.printf(
                        "  pos=%3d  txId=%-36s  status=%-30s  outputs=%d  stateChanges=%d%s%n",
                        i,
                        t.txId(),
                        status,
                        t.outputs().size(),
                        t.stateChanges().size(),
                        t.note().isEmpty() ? "" : "  [" + t.note() + "]");
            }
            return;
        }

        int matches = 0;
        for (int i = 0; i < txns.size(); i++) {
            final TxRecord t = txns.get(i);
            final boolean match = (posFilter >= 0 && i == posFilter) || (txFilter != null && txFilter.equals(t.txId()));
            if (!match) {
                continue;
            }
            matches++;
            System.out.printf("======== pos=%d  txId=%s ========%n", i, t.label());

            System.out.println("-- TRANSACTION BODY --");
            System.out.println(t.body() != null ? t.body() : "  (unavailable)");

            System.out.println("-- TRANSACTION RESULT --");
            System.out.println(t.result() != null ? t.result() : "  (none)");

            if (!t.outputs().isEmpty()) {
                System.out.println("-- TRANSACTION OUTPUT(S) --");
                for (final TransactionOutput o : t.outputs()) {
                    System.out.println(o);
                }
            }

            if (!t.stateChanges().isEmpty()) {
                System.out.println("-- STATE CHANGES --");
                for (final StateChange sc : t.stateChanges()) {
                    System.out.printf(
                            "  [%s (id=%d)] %s%n", safeStateName(sc.stateId()), sc.stateId(), sc.changeOperation());
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

    private static String safeStateName(final int stateId) {
        try {
            return BlockStreamUtils.stateNameOf(stateId);
        } catch (final Exception e) {
            return "state#" + stateId;
        }
    }

    private TxDump() {}
}
