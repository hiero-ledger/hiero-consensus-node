// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/// Finds the FIRST transaction in a round whose full OUTPUT RECORD diverges between two block dirs
/// (original vs re-minted) - comparing TransactionResult, TransactionOutput(s), and StateChanges, not just
/// status/gasUsed. The record running hash (which feeds PREVRANDAO) is chained over these output items, so a
/// state-change-only divergence (e.g. a randomness contract writing a different value with identical
/// status/gasUsed) poisons the hash while being invisible to a status/gasUsed comparison.
///
/// For each transaction it reports whether result / output / state changes differ, and for state changes it
/// lists the differing stateIds by name (via BlockStreamUtils.stateNameOf), so benign running-hash singleton
/// writes (BlockStreamService / PlatformState / RunningHashes) can be told apart from real execution
/// divergence (e.g. ContractService STORAGE).
///
/// Usage (single round):
///   java -cp ./hedera-state-validator-<ver>.jar com.hedera.statevalidation.blockstream.tools.OutputRecordCompare
/// --original <dir> --reminted <dir> --round 253747721 [--ignore-states a,b,c]
/// Usage (scan a range - finds the EARLIEST divergent output record across the whole replay):
///   ... com.hedera.statevalidation.blockstream.tools.OutputRecordCompare --original <dir> --reminted <dir>
/// --from-round N --to-round N \
///        [--ignore-states a,b,c] [--threads N] [--window N]
///
public final class OutputRecordCompare {

    public static void main(final String[] args) throws Exception {
        Path originalDir = null;
        Path remintedDir = null;
        long round = Long.MIN_VALUE;
        long fromRound = Long.MIN_VALUE;
        long toRound = Long.MAX_VALUE;
        int threads = Runtime.getRuntime().availableProcessors();
        int window = 0;
        Set<String> ignoreServices = Set.of();
        String dumpTx = null;
        boolean all = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--original" -> originalDir = Path.of(args[++i]);
                case "--reminted" -> remintedDir = Path.of(args[++i]);
                case "--round" -> round = Long.parseLong(args[++i]);
                case "--from-round" -> fromRound = Long.parseLong(args[++i]);
                case "--to-round" -> toRound = Long.parseLong(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--window" -> window = Integer.parseInt(args[++i]);
                case "--ignore-states" -> ignoreServices = Set.of(args[++i].split(","));
                case "--dump-tx" -> dumpTx = args[++i];
                case "--all" -> all = true;
                default -> {}
            }
        }
        if (originalDir == null || remintedDir == null) {
            System.err.println("Required: --original <dir> --reminted <dir> "
                    + "(--round N | --from-round N --to-round N) [--ignore-states a,b,c] [--threads N] [--window N]");
            System.exit(2);
        }
        if (threads < 1) {
            threads = 1;
        }
        if (window < 1) {
            window = threads * 8;
        }

        // Single-round mode (backwards compatible): report ALL divergent transactions in that one round.
        if (round != Long.MIN_VALUE) {
            singleRound(originalDir, remintedDir, round, ignoreServices, dumpTx);
            return;
        }

        // Range mode: scan blocks in ascending parallel windows; stop at the first block containing any
        // divergent output record and report the earliest divergent transaction in it. Because windows are
        // strictly ascending and all earlier blocks are confirmed clean, that is the global earliest divergence.
        final Map<Long, Path> original = indexByBlockNumber(originalDir);
        final Map<Long, Path> reminted = indexByBlockNumber(remintedDir);
        final long lo = Math.max(minKey(original), minKey(reminted));
        final long hi = Math.min(maxKey(original), maxKey(reminted));
        final Set<String> ignore = ignoreServices;
        final long fr = fromRound;
        final long tr = toRound;
        System.out.printf(
                "Scanning blocks [%d, %d], rounds [%s, %s], threads=%d, window=%d, ignoreStates=%s%n",
                lo,
                hi,
                fromRound == Long.MIN_VALUE ? "-inf" : Long.toString(fromRound),
                toRound == Long.MAX_VALUE ? "+inf" : Long.toString(toRound),
                threads,
                window,
                ignoreServices);

        final List<Long> blockNums = new ArrayList<>();
        for (long b = lo; b <= hi; b++) {
            if (original.containsKey(b) && reminted.containsKey(b)) {
                blockNums.add(b);
            }
        }

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        long blocksClean = 0;
        int divergentBlocks = 0;
        try {
            for (int start = 0; start < blockNums.size(); start += window) {
                final int end = Math.min(blockNums.size(), start + window);
                final List<Future<BlockDivergence>> futures = new ArrayList<>(end - start);
                for (int k = start; k < end; k++) {
                    final long blockNum = blockNums.get(k);
                    final Path oPath = original.get(blockNum);
                    final Path rPath = reminted.get(blockNum);
                    futures.add(pool.submit((Callable<BlockDivergence>)
                            () -> firstDivergenceInBlock(blockNum, oPath, rPath, fr, tr, ignore)));
                }
                BlockDivergence earliest = null;
                for (final Future<BlockDivergence> f : futures) {
                    final BlockDivergence d = f.get();
                    if (d == null) {
                        blocksClean++;
                    } else if (all) {
                        // Report every divergent block; keep scanning.
                        divergentBlocks++;
                        System.out.printf(
                                "DIVERGENT block %d, round %d: txId=%s diff=%s states=%s%n",
                                d.blockNum, d.round, d.txId, d.kinds, d.states);
                    } else if (earliest == null || d.blockNum < earliest.blockNum) {
                        earliest = d;
                    }
                }
                if (!all && earliest != null) {
                    System.out.println();
                    System.out.printf(
                            "EARLIEST divergent output record: block %d, round %d%n",
                            earliest.blockNum, earliest.round);
                    System.out.printf(
                            "  txId=%s  diff=%s  states=%s%n", earliest.txId, earliest.kinds, earliest.states);
                    System.out.printf("Blocks confirmed clean before it: at least %d%n", blocksClean);
                    return;
                }
                if (!all) {
                    System.out.printf("... %d blocks clean (through block %d)%n", blocksClean, blockNums.get(end - 1));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println();
        if (all) {
            System.out.printf(
                    "Scan complete: %d divergent block(s), %d clean block(s), %d total compared.%n",
                    divergentBlocks, blocksClean, blocksClean + divergentBlocks);
        } else {
            System.out.printf("No output-record divergence found across %d blocks.%n", blocksClean);
        }
    }

    /** Divergence descriptor for a block (the earliest divergent transaction within it). */
    private record BlockDivergence(long blockNum, long round, String txId, String kinds, String states) {}

    /**
     * Returns the earliest divergent transaction across all rounds in this block (within [fromRound, toRound]),
     * or null if the block's output records fully match. Rounds are walked in order; the first divergent
     * transaction found is the earliest in the block.
     */
    private static BlockDivergence firstDivergenceInBlock(
            final long blockNum,
            final Path oPath,
            final Path rPath,
            final long fromRound,
            final long toRound,
            final Set<String> ignore)
            throws IOException {
        final Block oBlock = BlockStreamAccess.blockFrom(oPath);
        final Block rBlock = BlockStreamAccess.blockFrom(rPath);

        // Determine which rounds this block carries (from the original), in order, within the window.
        final List<Long> rounds = new ArrayList<>();
        for (final BlockItem item : oBlock.items()) {
            if (item.hasRoundHeader()) {
                final long rd = item.roundHeader().roundNumber();
                if (rd >= fromRound && rd <= toRound) {
                    rounds.add(rd);
                }
            }
        }
        for (final long rd : rounds) {
            final List<TxRecord> oTx = extractRound(oBlock, rd);
            final List<TxRecord> rTx = extractRound(rBlock, rd);

            // (a) STATE: compare at ROUND level, not per-transaction. State-change grouping into StateChanges
            // items is a serialization detail and their textual attachment to a transaction is unreliable, so
            // aggregate ALL of the round's state changes by stateId and compare the sets. This avoids both the
            // synthetic-txId collision and the per-tx attribution ambiguity.
            final List<StateChange> oAll = new ArrayList<>();
            for (final TxRecord t : oTx) {
                oAll.addAll(t.stateChanges);
            }
            final List<StateChange> rAll = new ArrayList<>();
            for (final TxRecord t : rTx) {
                rAll.addAll(t.stateChanges);
            }
            final Set<String> changedStates = differingStateServices(oAll, rAll, ignore);

            // (b) RESULT/OUTPUT: compare by POSITION (i-th original vs i-th reminted). Round ordering is
            // identical between the two sides for matched rounds, so position is the correct pairing and is
            // immune to synthetic-txId collisions.
            String firstResultOutputDivTx = null;
            String firstKinds = null;
            final int n = Math.min(oTx.size(), rTx.size());
            for (int i = 0; i < n; i++) {
                final TxRecord o = oTx.get(i);
                final TxRecord r = rTx.get(i);
                final boolean idDiff = !Objects.equals(o.txId, r.txId);
                final boolean resultDiff = !Objects.equals(o.result, r.result);
                final boolean outputDiff = !Objects.equals(o.outputs, r.outputs);
                if (idDiff || resultDiff || outputDiff) {
                    final var kinds = new ArrayList<String>();
                    if (idDiff) {
                        kinds.add("TXID(" + o.txId + " vs " + r.txId + ")");
                    }
                    if (resultDiff) {
                        kinds.add("RESULT");
                    }
                    if (outputDiff) {
                        kinds.add("OUTPUT");
                    }
                    firstResultOutputDivTx = o.txId + " @pos" + i;
                    firstKinds = kinds.toString();
                    break;
                }
            }
            final boolean countDiff = oTx.size() != rTx.size();

            if (firstResultOutputDivTx != null || !changedStates.isEmpty() || countDiff) {
                final var kinds = new ArrayList<String>();
                if (firstResultOutputDivTx != null) {
                    kinds.add(firstKinds);
                }
                if (!changedStates.isEmpty()) {
                    kinds.add("STATE(round-level)");
                }
                if (countDiff) {
                    kinds.add("TXCOUNT(" + oTx.size() + " vs " + rTx.size() + ")");
                }
                final String label = firstResultOutputDivTx != null
                        ? firstResultOutputDivTx
                        : ("round " + rd + " (state/count only)");
                return new BlockDivergence(
                        blockNum, rd, label, kinds.toString(), changedStates.isEmpty() ? "" : changedStates.toString());
            }
        }
        return null;
    }

    private static long minKey(final Map<Long, Path> m) {
        return m.keySet().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
    }

    private static long maxKey(final Map<Long, Path> m) {
        return m.keySet().stream().mapToLong(Long::longValue).max().orElse(Long.MIN_VALUE);
    }

    /** Original single-round behaviour: report every divergent transaction in one round. */
    private static void singleRound(
            final Path originalDir,
            final Path remintedDir,
            final long round,
            final Set<String> ignoreServices,
            final String dumpTx)
            throws IOException {
        final Path oFile = findBlockContainingRound(originalDir, round);
        final Path rFile = findBlockContainingRound(remintedDir, round);
        if (oFile == null || rFile == null) {
            System.err.printf("Round %d not found in %s%n", round, oFile == null ? "original" : "reminted");
            System.exit(1);
        }
        System.out.printf(
                "Round %d  original=%s  reminted=%s  ignoreStates=%s%n%n",
                round, oFile.getFileName(), rFile.getFileName(), ignoreServices);

        final List<TxRecord> oTx = extractRound(BlockStreamAccess.blockFrom(oFile), round);
        final List<TxRecord> rTx = extractRound(BlockStreamAccess.blockFrom(rFile), round);

        final Map<String, TxRecord> rById = new LinkedHashMap<>();
        for (final TxRecord t : rTx) {
            rById.putIfAbsent(t.txId, t);
        }

        System.out.printf("Transactions in round: original=%d, reminted=%d%n%n", oTx.size(), rTx.size());

        int firstDivergentPos = -1;
        int divergentCount = 0;
        for (int i = 0; i < oTx.size(); i++) {
            final TxRecord o = oTx.get(i);
            final TxRecord r = rById.get(o.txId);
            if (r == null) {
                report(i, o.txId, "MISSING in reminted", "", "", "");
                if (firstDivergentPos < 0) {
                    firstDivergentPos = i;
                }
                divergentCount++;
                continue;
            }
            final boolean resultDiff = !Objects.equals(o.result, r.result);
            final boolean outputDiff = !Objects.equals(o.outputs, r.outputs);
            final Set<String> changedStates = differingStateServices(o.stateChanges, r.stateChanges, ignoreServices);
            final boolean stateDiff = !changedStates.isEmpty();

            if (resultDiff || outputDiff || stateDiff) {
                if (firstDivergentPos < 0) {
                    firstDivergentPos = i;
                }
                divergentCount++;
                report(
                        i,
                        o.txId,
                        resultDiff ? "RESULT" : "",
                        outputDiff ? "OUTPUT" : "",
                        stateDiff ? "STATE" : "",
                        stateDiff ? changedStates.toString() : "");
                if (dumpTx != null && dumpTx.equals(o.txId)) {
                    dumpDifferences(o, r, ignoreServices);
                }
            }
        }

        System.out.println();
        System.out.println("==== SUMMARY ====");
        if (firstDivergentPos < 0) {
            System.out.println("No output-record divergence in this round (result/output/state all match, "
                    + "modulo ignored states).");
        } else {
            final TxRecord first = oTx.get(firstDivergentPos);
            System.out.printf("FIRST divergent transaction: pos=%d txId=%s%n", firstDivergentPos, first.txId);
            System.out.printf("Total divergent transactions in round: %d of %d%n", divergentCount, oTx.size());
            System.out.println();
            System.out.println("If the first divergence is STATE-only (result+output identical) on a contract "
                    + "storage state, it is a state-change-only divergence - invisible to status/gasUsed "
                    + "comparison - and is the true seed of the running-hash divergence.");
        }
    }

    /**
     * Prints the actual differing values for one transaction: result, outputs, and per-stateId state changes
     * (original vs reminted), so the concrete difference (node ids, amounts) is visible. Uses PBJ toString().
     */
    private static void dumpDifferences(final TxRecord o, final TxRecord r, final Set<String> ignoreServices) {
        System.out.println();
        System.out.printf("==== VALUE DUMP for txId=%s ====%n", o.txId);
        if (!Objects.equals(o.result, r.result)) {
            System.out.println("-- RESULT --");
            System.out.println("  original: " + o.result);
            System.out.println("  reminted: " + r.result);
        }
        if (!Objects.equals(o.outputs, r.outputs)) {
            System.out.println("-- OUTPUT --");
            System.out.println("  original: " + o.outputs);
            System.out.println("  reminted: " + r.outputs);
        }
        final Map<Integer, List<StateChange>> aById = groupByState(o.stateChanges);
        final Map<Integer, List<StateChange>> bById = groupByState(r.stateChanges);
        final Set<Integer> allIds = new java.util.TreeSet<>();
        allIds.addAll(aById.keySet());
        allIds.addAll(bById.keySet());
        for (final int id : allIds) {
            final String stateName = safeStateName(id);
            final String service = serviceOf(stateName);
            if (ignoreServices.stream().anyMatch(service::startsWith)) {
                continue;
            }
            final List<StateChange> aList = aById.getOrDefault(id, List.of());
            final List<StateChange> bList = bById.getOrDefault(id, List.of());
            if (Objects.equals(aList, bList)) {
                continue;
            }
            System.out.printf("-- STATE %s (id=%d) --%n", stateName, id);
            System.out.println("  original:");
            for (final StateChange sc : aList) {
                System.out.println("    " + sc);
            }
            System.out.println("  reminted:");
            for (final StateChange sc : bList) {
                System.out.println("    " + sc);
            }
        }
        System.out.println("==== END VALUE DUMP ====");
        System.out.println();
    }

    private static void report(
            final int pos, final String txId, final String a, final String b, final String c, final String states) {
        final var kinds = new ArrayList<String>();
        if (!a.isEmpty()) {
            kinds.add(a);
        }
        if (!b.isEmpty()) {
            kinds.add(b);
        }
        if (!c.isEmpty()) {
            kinds.add(c);
        }
        System.out.printf("  pos=%d  txId=%-30s  diff=%s  %s%n", pos, txId, kinds, states);
    }

    private record TxRecord(String txId, Object result, List<Object> outputs, List<StateChange> stateChanges) {}

    /**
     * Returns the set of service names whose NET state effect differs between the two change lists.
     * To avoid false positives from serialization/grouping differences, this compares the NET effect per
     * stateId rather than the raw ordered change lists:
     *   - singleton: the LAST singleton value written wins (final value).
     *   - map: the net map of key -> last update value (deletes remove keys).
     *   - queue/other: falls back to comparing the ordered change list.
     * A service is reported only if its net effect actually differs (and it is not ignored).
     */
    private static Set<String> differingStateServices(
            final List<StateChange> a, final List<StateChange> b, final Set<String> ignoreServices) {
        final Map<Integer, Object> aNet = netEffectByState(a);
        final Map<Integer, Object> bNet = netEffectByState(b);
        final Set<Integer> allIds = new java.util.HashSet<>();
        allIds.addAll(aNet.keySet());
        allIds.addAll(bNet.keySet());
        final Set<String> differing = new java.util.TreeSet<>();
        for (final int id : allIds) {
            final String stateName = safeStateName(id);
            final String service = serviceOf(stateName);
            if (ignoreServices.stream().anyMatch(service::startsWith)) {
                continue;
            }
            if (!Objects.equals(aNet.get(id), bNet.get(id))) {
                differing.add(stateName);
            }
        }
        return differing;
    }

    /**
     * Computes the net end-state effect per stateId from a list of state changes:
     *   - singleton -> the last singleton value (the SingletonUpdateChange's newValue).
     *   - map       -> a Map of net key->value (MAP_UPDATE puts, MAP_DELETE removes).
     *   - otherwise -> the ordered list of change operations (queue pushes/pops, etc.).
     */
    private static Map<Integer, Object> netEffectByState(final List<StateChange> changes) {
        final Map<Integer, Object> singletonFinal = new TreeMap<>();
        final Map<Integer, Map<Object, Object>> mapNet = new TreeMap<>();
        final Map<Integer, List<Object>> otherOrdered = new TreeMap<>();
        for (final StateChange sc : changes) {
            final int id = sc.stateId();
            switch (sc.changeOperation().kind()) {
                case SINGLETON_UPDATE ->
                    singletonFinal.put(id, sc.singletonUpdateOrThrow().newValue());
                case MAP_UPDATE -> {
                    final var mu = sc.mapUpdateOrThrow();
                    mapNet.computeIfAbsent(id, k -> new java.util.HashMap<>()).put(mu.keyOrThrow(), mu.valueOrThrow());
                }
                case MAP_DELETE ->
                    mapNet.computeIfAbsent(id, k -> new java.util.HashMap<>())
                            .remove(sc.mapDeleteOrThrow().keyOrThrow());
                default ->
                    otherOrdered.computeIfAbsent(id, k -> new ArrayList<>()).add(sc.changeOperation());
            }
        }
        final Map<Integer, Object> out = new TreeMap<>();
        singletonFinal.forEach(out::put);
        mapNet.forEach(out::put);
        otherOrdered.forEach((id, list) -> out.merge(id, list, (x, y) -> list));
        return out;
    }

    private static Map<Integer, List<StateChange>> groupByState(final List<StateChange> changes) {
        final Map<Integer, List<StateChange>> m = new TreeMap<>();
        for (final StateChange sc : changes) {
            m.computeIfAbsent(sc.stateId(), k -> new ArrayList<>()).add(sc);
        }
        return m;
    }

    private static String safeStateName(final int stateId) {
        try {
            return BlockStreamUtils.stateNameOf(stateId);
        } catch (final Exception e) {
            return "state#" + stateId;
        }
    }

    private static String serviceOf(final String stateName) {
        final int dot = stateName.indexOf('.');
        return dot == -1 ? stateName : stateName.substring(0, dot);
    }

    /**
     * Walks the block, and within the target round groups items into per-transaction records: the
     * SIGNED_TRANSACTION starts a tx; the following TRANSACTION_RESULT, TRANSACTION_OUTPUT(s) and the
     * STATE_CHANGES emitted for it are attributed to it until the next SIGNED_TRANSACTION or ROUND_HEADER.
     */
    private static List<TxRecord> extractRound(final Block block, final long round) {
        final List<TxRecord> out = new ArrayList<>();
        long current = -1;
        boolean inRound = false;

        String txId = null;
        Object result = null;
        List<Object> outputs = new ArrayList<>();
        List<StateChange> stateChanges = new ArrayList<>();
        boolean have = false;

        for (final BlockItem item : block.items()) {
            if (item.hasRoundHeader()) {
                if (have && inRound) {
                    out.add(new TxRecord(txId, result, outputs, stateChanges));
                }
                have = false;
                txId = null;
                result = null;
                outputs = new ArrayList<>();
                stateChanges = new ArrayList<>();
                current = item.roundHeader().roundNumber();
                inRound = current == round;
                continue;
            }
            if (!inRound) {
                continue;
            }
            switch (item.item().kind()) {
                case SIGNED_TRANSACTION -> {
                    if (have) {
                        out.add(new TxRecord(txId, result, outputs, stateChanges));
                    }
                    txId = txIdString(item.item().as());
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
                    /* ignore event headers, trace, proofs, headers */
                }
            }
        }
        if (have && inRound) {
            out.add(new TxRecord(txId, result, outputs, stateChanges));
        }
        return out;
    }

    private static String txIdString(final Bytes transactionBytes) {
        try {
            final SignedTransaction st = SignedTransaction.PROTOBUF.parse(transactionBytes.toReadableSequentialData());
            final TransactionBody body =
                    TransactionBody.PROTOBUF.parse(st.bodyBytes().toReadableSequentialData());
            final TransactionID id = body.transactionID();
            if (id == null) {
                return "<no-id>";
            }
            final var acct = id.accountID();
            final long num = acct == null ? -1 : acct.accountNumOrElse(-1L);
            final var start = id.transactionValidStart();
            final long sec = start == null ? -1 : start.seconds();
            final int nanos = start == null ? -1 : start.nanos();
            return num + "@" + sec + "." + nanos + (id.nonce() != 0 ? ".n" + id.nonce() : "")
                    + (id.scheduled() ? ".sched" : "");
        } catch (final Exception e) {
            return "<unparseable>";
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

    private static Map<Long, Path> indexByBlockNumber(final Path dir) throws IOException {
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

    private OutputRecordCompare() {}
}
