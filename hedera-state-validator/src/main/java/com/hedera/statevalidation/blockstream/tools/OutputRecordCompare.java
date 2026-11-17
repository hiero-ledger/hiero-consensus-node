// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.statevalidation.blockstream.tools.BlockRoundExtractor.RoundContent;
import com.hedera.statevalidation.blockstream.tools.BlockRoundExtractor.TxRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /** Final value recorded for a map key that was deleted in the round. */
    private enum Marker {
        DELETED
    }

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
            System.err.println(
                    "Required: --original <dir> --reminted <dir> "
                            + "(--round N | [--from-round N] [--to-round N]) [--ignore-states a,b,c] [--threads N] [--window N]");
            System.exit(2);
        }
        if (threads < 1) {
            threads = 1;
        }
        if (window < 1) {
            window = threads * 8;
        }

        if (round != Long.MIN_VALUE) {
            singleRound(originalDir, remintedDir, round, ignoreServices, dumpTx);
            return;
        }

        // Range mode: scan blocks in ascending parallel windows and stop at the first block with a divergence.
        // All earlier blocks are confirmed clean, so that is the earliest divergence overall.
        final Map<Long, Path> original = BlockRoundExtractor.indexByBlockNumber(originalDir);
        final Map<Long, Path> reminted = BlockRoundExtractor.indexByBlockNumber(remintedDir);
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
                // Futures are in ascending block order, so the first divergent one is the earliest.
                BlockDivergence earliest = null;
                for (final Future<BlockDivergence> f : futures) {
                    final BlockDivergence d = f.get();
                    if (d == null) {
                        blocksClean++;
                        continue;
                    }
                    if (all) {
                        divergentBlocks++;
                        System.out.printf(
                                "DIVERGENT block %d, round %d: txId=%s diff=%s states=%s%n",
                                d.blockNum, d.round, d.txId, d.kinds, d.states);
                        continue;
                    }
                    earliest = d;
                    // Stop here: blocks after the divergence must not be counted as clean before it. The remaining
                    // tasks are cancelled by pool.shutdownNow() in the finally block.
                    break;
                }
                if (!all && earliest != null) {
                    System.out.println();
                    System.out.printf(
                            "EARLIEST divergent output record: block %d, round %d%n",
                            earliest.blockNum, earliest.round);
                    System.out.printf(
                            "  txId=%s  diff=%s  states=%s%n", earliest.txId, earliest.kinds, earliest.states);
                    System.out.printf("Blocks clean before it: %d%n", blocksClean);
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

    /** The earliest divergence found in a block. */
    private record BlockDivergence(long blockNum, long round, String txId, String kinds, String states) {}

    /**
     * Returns the earliest divergence in this block within [fromRound, toRound], or {@code null} if the block
     * matches. Rounds are taken from both blocks, so a round present on only one side is reported.
     */
    private static BlockDivergence firstDivergenceInBlock(
            final long blockNum,
            final Path oPath,
            final Path rPath,
            final long fromRound,
            final long toRound,
            final Set<String> ignore) {
        final Block oBlock = BlockStreamAccess.blockFrom(oPath);
        final Block rBlock = BlockStreamAccess.blockFrom(rPath);

        final TreeSet<Long> rounds = new TreeSet<>(BlockRoundExtractor.roundsIn(oBlock, fromRound, toRound));
        rounds.addAll(BlockRoundExtractor.roundsIn(rBlock, fromRound, toRound));

        for (final long rd : rounds) {
            final RoundContent o = BlockRoundExtractor.extract(oBlock, rd);
            final RoundContent r = BlockRoundExtractor.extract(rBlock, rd);

            if (!o.present() || !r.present()) {
                return new BlockDivergence(
                        blockNum,
                        rd,
                        "round " + rd,
                        "[ROUND(only in " + (o.present() ? "original" : "reminted") + ")]",
                        "");
            }

            // State: compared at round level over every state change in the round, including those before the
            // first transaction. How changes are grouped into items is a serialization detail.
            final Set<String> changedStates = differingStateServices(o.allStateChanges(), r.allStateChanges(), ignore);

            // Result/output: compared by position.
            final List<TxRecord> oTx = o.transactions();
            final List<TxRecord> rTx = r.transactions();
            String firstTx = null;
            String firstKinds = null;
            final int n = Math.min(oTx.size(), rTx.size());
            for (int i = 0; i < n; i++) {
                final List<String> kinds = transactionDiffKinds(oTx.get(i), rTx.get(i));
                if (!kinds.isEmpty()) {
                    firstTx = oTx.get(i).label() + " @pos" + i;
                    firstKinds = kinds.toString();
                    break;
                }
            }
            final boolean countDiff = oTx.size() != rTx.size();

            if (firstTx != null || !changedStates.isEmpty() || countDiff) {
                final List<String> kinds = new ArrayList<>();
                if (firstKinds != null) {
                    kinds.add(firstKinds);
                }
                if (!changedStates.isEmpty()) {
                    kinds.add("STATE(round-level)");
                }
                if (countDiff) {
                    kinds.add("TXCOUNT(" + oTx.size() + " vs " + rTx.size() + ")");
                }
                final String label = firstTx != null ? firstTx : ("round " + rd + " (state/count only)");
                return new BlockDivergence(
                        blockNum, rd, label, kinds.toString(), changedStates.isEmpty() ? "" : changedStates.toString());
            }
        }
        return null;
    }

    /** TXID / RESULT / OUTPUT differences between two transactions at the same position. */
    private static List<String> transactionDiffKinds(final TxRecord o, final TxRecord r) {
        final List<String> kinds = new ArrayList<>();
        if (!Objects.equals(o.txId(), r.txId())) {
            kinds.add("TXID(" + o.txId() + " vs " + r.txId() + ")");
        }
        if (!Objects.equals(o.result(), r.result())) {
            kinds.add("RESULT");
        }
        if (!Objects.equals(o.outputs(), r.outputs())) {
            kinds.add("OUTPUT");
        }
        return kinds;
    }

    /** Reports every divergent transaction in one round, matching transactions by position. */
    private static void singleRound(
            final Path originalDir,
            final Path remintedDir,
            final long round,
            final Set<String> ignoreServices,
            final String dumpTx)
            throws IOException {
        final Path oFile = BlockRoundExtractor.findBlockContainingRound(originalDir, round);
        final Path rFile = BlockRoundExtractor.findBlockContainingRound(remintedDir, round);
        if (oFile == null || rFile == null) {
            System.err.printf("Round %d not found in %s%n", round, oFile == null ? "original" : "reminted");
            System.exit(1);
        }
        System.out.printf(
                "Round %d  original=%s  reminted=%s  ignoreStates=%s%n%n",
                round, oFile.getFileName(), rFile.getFileName(), ignoreServices);

        final RoundContent o = BlockRoundExtractor.extract(BlockStreamAccess.blockFrom(oFile), round);
        final RoundContent r = BlockRoundExtractor.extract(BlockStreamAccess.blockFrom(rFile), round);
        final List<TxRecord> oTx = o.transactions();
        final List<TxRecord> rTx = r.transactions();

        System.out.printf("Transactions in round: original=%d, reminted=%d%n%n", oTx.size(), rTx.size());

        int firstDivergentPos = -1;
        int divergentCount = 0;
        final int n = Math.max(oTx.size(), rTx.size());
        for (int i = 0; i < n; i++) {
            final TxRecord ot = i < oTx.size() ? oTx.get(i) : null;
            final TxRecord rt = i < rTx.size() ? rTx.get(i) : null;
            final List<String> kinds;
            String states = "";
            final String label;
            if (ot == null) {
                kinds = List.of("EXTRA in reminted");
                label = rt.label();
            } else if (rt == null) {
                kinds = List.of("MISSING in reminted");
                label = ot.label();
            } else {
                kinds = new ArrayList<>(transactionDiffKinds(ot, rt));
                final Set<String> changedStates =
                        differingStateServices(ot.stateChanges(), rt.stateChanges(), ignoreServices);
                if (!changedStates.isEmpty()) {
                    kinds.add("STATE");
                    states = changedStates.toString();
                }
                label = ot.label();
            }
            if (kinds.isEmpty()) {
                continue;
            }
            if (firstDivergentPos < 0) {
                firstDivergentPos = i;
            }
            divergentCount++;
            System.out.printf("  pos=%d  txId=%-30s  diff=%s  %s%n", i, label, kinds, states);
            if (dumpTx != null && ot != null && rt != null && dumpTx.equals(ot.txId())) {
                dumpDifferences(ot, rt, ignoreServices);
            }
        }

        // Round-level state: every state change in the round, including those before the first transaction.
        final Set<String> roundStates =
                differingStateServices(o.allStateChanges(), r.allStateChanges(), ignoreServices);
        System.out.println();
        System.out.println("Round-level state: " + (roundStates.isEmpty() ? "match" : "differs in " + roundStates));
        if (!o.unattributedStateChanges().isEmpty()
                || !r.unattributedStateChanges().isEmpty()) {
            System.out.printf(
                    "State changes before the first transaction: original=%d, reminted=%d%n",
                    o.unattributedStateChanges().size(),
                    r.unattributedStateChanges().size());
        }

        System.out.println();
        System.out.println("==== SUMMARY ====");
        if (firstDivergentPos < 0 && roundStates.isEmpty()) {
            System.out.println("No output-record divergence in this round (result/output/state all match, "
                    + "modulo ignored states).");
        } else if (firstDivergentPos < 0) {
            System.out.println("Transactions match, but the round-level state differs in " + roundStates);
        } else {
            final TxRecord first =
                    firstDivergentPos < oTx.size() ? oTx.get(firstDivergentPos) : rTx.get(firstDivergentPos);
            System.out.printf("FIRST divergent transaction: pos=%d txId=%s%n", firstDivergentPos, first.label());
            System.out.printf("Total divergent transactions in round: %d of %d%n", divergentCount, n);
        }
    }

    /** Prints the differing values of one transaction: result, outputs and state changes per state. */
    private static void dumpDifferences(final TxRecord o, final TxRecord r, final Set<String> ignoreServices) {
        System.out.println();
        System.out.printf("==== VALUE DUMP for txId=%s ====%n", o.label());
        if (!Objects.equals(o.result(), r.result())) {
            System.out.println("-- RESULT --");
            System.out.println("  original: " + o.result());
            System.out.println("  reminted: " + r.result());
        }
        if (!Objects.equals(o.outputs(), r.outputs())) {
            System.out.println("-- OUTPUT --");
            System.out.println("  original: " + o.outputs());
            System.out.println("  reminted: " + r.outputs());
        }
        final Map<Integer, List<StateChange>> aById = groupByState(o.stateChanges());
        final Map<Integer, List<StateChange>> bById = groupByState(r.stateChanges());
        final Set<Integer> allIds = new TreeSet<>();
        allIds.addAll(aById.keySet());
        allIds.addAll(bById.keySet());
        for (final int id : allIds) {
            final String stateName = safeStateName(id);
            if (isIgnored(stateName, ignoreServices)) {
                continue;
            }
            final List<StateChange> aList = aById.getOrDefault(id, List.of());
            final List<StateChange> bList = bById.getOrDefault(id, List.of());
            if (Objects.equals(aList, bList)) {
                continue;
            }
            System.out.printf("-- STATE %s (id=%d) --%n", stateName, id);
            System.out.println("  original:");
            aList.forEach(sc -> System.out.println("    " + sc));
            System.out.println("  reminted:");
            bList.forEach(sc -> System.out.println("    " + sc));
        }
        System.out.println("==== END VALUE DUMP ====");
        System.out.println();
    }

    /**
     * Names of the states whose net effect differs between the two change lists, excluding ignored services.
     * Net effect per state: last value for singletons, final key/value (or deletion) per key for maps, ordered
     * list of operations otherwise.
     */
    private static Set<String> differingStateServices(
            final List<StateChange> a, final List<StateChange> b, final Set<String> ignoreServices) {
        final Map<Integer, Object> aNet = netEffectByState(a);
        final Map<Integer, Object> bNet = netEffectByState(b);
        final Set<Integer> allIds = new HashSet<>();
        allIds.addAll(aNet.keySet());
        allIds.addAll(bNet.keySet());
        final Set<String> differing = new TreeSet<>();
        for (final int id : allIds) {
            final String stateName = safeStateName(id);
            if (isIgnored(stateName, ignoreServices)) {
                continue;
            }
            if (!Objects.equals(aNet.get(id), bNet.get(id))) {
                differing.add(stateName);
            }
        }
        return differing;
    }

    /**
     * Net end-of-round effect per state:
     * <ul>
     *   <li>singleton: the last value written;</li>
     *   <li>map: the final value per key, where a deletion is kept as {@link Marker#DELETED} so that a key deleted
     *       on only one side is still detected;</li>
     *   <li>anything else (queues): the ordered list of operations.</li>
     * </ul>
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
                    mapNet.computeIfAbsent(id, k -> new HashMap<>()).put(mu.keyOrThrow(), mu.valueOrThrow());
                }
                case MAP_DELETE ->
                    mapNet.computeIfAbsent(id, k -> new HashMap<>())
                            .put(sc.mapDeleteOrThrow().keyOrThrow(), Marker.DELETED);
                default ->
                    otherOrdered.computeIfAbsent(id, k -> new ArrayList<>()).add(sc.changeOperation());
            }
        }
        final Map<Integer, Object> out = new TreeMap<>();
        out.putAll(singletonFinal);
        out.putAll(mapNet);
        out.putAll(otherOrdered);
        return out;
    }

    private static Map<Integer, List<StateChange>> groupByState(final List<StateChange> changes) {
        final Map<Integer, List<StateChange>> m = new TreeMap<>();
        for (final StateChange sc : changes) {
            m.computeIfAbsent(sc.stateId(), k -> new ArrayList<>()).add(sc);
        }
        return m;
    }

    private static boolean isIgnored(final String stateName, final Set<String> ignoreServices) {
        final String service = serviceOf(stateName);
        return ignoreServices.stream().anyMatch(service::startsWith);
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

    private static long minKey(final Map<Long, Path> m) {
        return m.keySet().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
    }

    private static long maxKey(final Map<Long, Path> m) {
        return m.keySet().stream().mapToLong(Long::longValue).max().orElse(Long.MIN_VALUE);
    }

    private OutputRecordCompare() {}
}
