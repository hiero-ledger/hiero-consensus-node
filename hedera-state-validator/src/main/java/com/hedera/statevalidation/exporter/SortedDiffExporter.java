// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporter;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.statevalidation.exporter.SortedJsonExporter.SINGLE_STATE_TMPL;
import static com.hedera.statevalidation.exporter.SortedJsonExporter.keyComparatorFor;
import static com.hedera.statevalidation.exporter.SortedJsonExporter.writeEntry;
import static com.hedera.statevalidation.util.ConfigUtils.MAX_OBJ_PER_FILE;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.ParallelProcessingUtils;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Exports the differences between two states as sorted JSON files, grouped by service and state key
 * (the same layout as {@link SortedJsonExporter}). For each tracked state:
 * <ul>
 *   <li>{@code state1/} holds deletions (present in state1, absent in state2) and the old side of modifications;</li>
 *   <li>{@code state2/} holds additions (present in state2, absent in state1) and the new side of modifications.</li>
 * </ul>
 * Each subdirectory is directly comparable, file by file, to a sorted export of the corresponding single state.
 */
public class SortedDiffExporter {

    private static final Logger log = LogManager.getLogger(SortedDiffExporter.class);

    private static final String STATE_1_DIR = "state1";
    private static final String STATE_2_DIR = "state2";

    private final File resultDir;
    private final VirtualMapState state1;
    private final VirtualMapState state2;
    private final ExecutorService executorService;

    /** Deletions + old side of modifications, keyed by stateId; paths refer to state1. */
    private final Map<Integer, Set<Pair<Long, Bytes>>> state1DiffByStateId;
    /** Additions + new side of modifications, keyed by stateId; paths refer to state2. */
    private final Map<Integer, Set<Pair<Long, Bytes>>> state2DiffByStateId;

    private final Map<Integer, Pair<String, String>> nameByStateId;

    private final AtomicLong objectsProcessed = new AtomicLong(0);

    public SortedDiffExporter(
            @NonNull final File resultDir,
            @NonNull final VirtualMapState state1,
            @NonNull final VirtualMapState state2,
            @Nullable final String serviceName,
            @Nullable final String stateKey) {
        this(resultDir, state1, state2, List.of(Pair.of(serviceName, stateKey)));
    }

    public SortedDiffExporter(
            @NonNull final File resultDir,
            @NonNull final VirtualMapState state1,
            @NonNull final VirtualMapState state2,
            @NonNull final List<Pair<String, String>> serviceNameStateKeyList) {
        this.resultDir = resultDir;
        this.state1 = state1;
        this.state2 = state2;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.state1DiffByStateId = new HashMap<>();
        this.state2DiffByStateId = new HashMap<>();
        this.nameByStateId = new HashMap<>();

        serviceNameStateKeyList.forEach(p -> {
            final int stateId = StateUtils.stateIdFor(p.left(), p.right());
            final Comparator<Pair<Long, Bytes>> comparator = keyComparatorFor(stateId);
            state1DiffByStateId.computeIfAbsent(stateId, _ -> new ConcurrentSkipListSet<>(comparator));
            state2DiffByStateId.computeIfAbsent(stateId, _ -> new ConcurrentSkipListSet<>(comparator));
            nameByStateId.put(stateId, p);
        });
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm1 = state1.getRoot();
        final VirtualMap vm2 = state2.getRoot();
        log.info("Start comparing states");

        // state1 -> state2 : deletions and modifications
        final CompletableFuture<Void> firstPass = CompletableFuture.runAsync(() -> compare(vm1, vm2, true));
        // state2 -> state1 : additions
        final CompletableFuture<Void> secondPass = CompletableFuture.runAsync(() -> compare(vm2, vm1, false));
        CompletableFuture.allOf(firstPass, secondPass).join();

        try {
            final File dir1 = new File(resultDir, STATE_1_DIR);
            final File dir2 = new File(resultDir, STATE_2_DIR);
            Files.createDirectories(dir1.toPath());
            Files.createDirectories(dir2.toPath());

            final List<CompletableFuture<Void>> writes = new ArrayList<>();
            writes.addAll(writeInParallel(state1DiffByStateId, vm1, dir1));
            writes.addAll(writeInParallel(state2DiffByStateId, vm2, dir2));
            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).join();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.close();
        }

        log.info("Diff time: {} seconds", (System.currentTimeMillis() - startTimestamp) / 1000);
    }

    /**
     * Traverses {@code vmSource} in parallel and compares each tracked entry against {@code vmTarget}.
     *
     * @param isFirstPass when true, collects deletions and modifications; when false, collects additions
     */
    private void compare(
            @NonNull final VirtualMap vmSource, @NonNull final VirtualMap vmTarget, final boolean isFirstPass) {
        final VirtualMap.Metadata metadata = vmSource.getMetadata();
        ParallelProcessingUtils.processRange(metadata.getFirstLeafPath(), metadata.getLastLeafPath() + 1, path -> {
                    final long count = objectsProcessed.incrementAndGet();
                    if (count % 1_000_000 == 0) {
                        log.info("Objects processed: {}", count);
                    }

                    VirtualLeafBytes<?> sourceLeaf = null;
                    try {
                        sourceLeaf = vmSource.getRecords().findLeafRecord(path);
                    } catch (final Exception e) {
                        log.error("Unexpected error while finding leaf record by path in source", e);
                    }
                    if (sourceLeaf == null) {
                        return;
                    }

                    final Bytes keyBytes = sourceLeaf.keyBytes();
                    final int stateId = resolveStateId(keyBytes);
                    final Set<Pair<Long, Bytes>> sourceBucket =
                            isFirstPass ? state1DiffByStateId.get(stateId) : state2DiffByStateId.get(stateId);
                    if (sourceBucket == null) {
                        return; // not a tracked state
                    }

                    final Bytes sourceValueBytes = sourceLeaf.valueBytes();
                    final VirtualLeafBytes<?> targetLeaf = vmTarget.getRecords().findLeafRecord(keyBytes);

                    if (isFirstPass) {
                        if (targetLeaf == null) {
                            // Deletion: present in state1, absent in state2
                            sourceBucket.add(Pair.of(path, keyBytes));
                        } else if (!Objects.equals(sourceValueBytes, targetLeaf.valueBytes())) {
                            // Modification: old side to state1, new side to state2
                            sourceBucket.add(Pair.of(path, keyBytes));
                            state2DiffByStateId.get(stateId).add(Pair.of(targetLeaf.path(), keyBytes));
                        }
                        // Equal: skip
                    } else if (targetLeaf == null) {
                        // Addition: present in state2, absent in state1
                        sourceBucket.add(Pair.of(path, keyBytes));
                    }
                })
                .join();
    }

    private List<CompletableFuture<Void>> writeInParallel(
            @NonNull final Map<Integer, Set<Pair<Long, Bytes>>> diffByStateId,
            @NonNull final VirtualMap vm,
            @NonNull final File dir) {
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final Map.Entry<Integer, Set<Pair<Long, Bytes>>> entry : diffByStateId.entrySet()) {
            final List<Pair<Long, Bytes>> keys = new ArrayList<>(entry.getValue());
            if (keys.isEmpty()) {
                continue;
            }
            final Pair<String, String> namePair = nameByStateId.get(entry.getKey());
            final int fileCount = keys.size() / MAX_OBJ_PER_FILE;
            for (int i = 0; i <= fileCount; i++) {
                final String fileName = String.format(SINGLE_STATE_TMPL, namePair.left(), namePair.right(), i + 1);
                final int start = i * MAX_OBJ_PER_FILE;
                final int end = Math.min((i + 1) * MAX_OBJ_PER_FILE, keys.size()) - 1;
                futures.add(CompletableFuture.runAsync(
                        () -> processRange(keys, vm, dir, fileName, start, end), executorService));
            }
        }
        return futures;
    }

    private void processRange(
            @NonNull final List<Pair<Long, Bytes>> keys,
            @NonNull final VirtualMap vm,
            @NonNull final File dir,
            @NonNull final String fileName,
            final int start,
            final int end) {
        final File file = new File(dir, fileName);
        boolean emptyFile = true;
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (int i = start; i <= end; i++) {
                final long path = keys.get(i).left();
                final Bytes keyBytes = keys.get(i).right();
                final VirtualLeafBytes<?> leafRecord = vm.getRecords().findLeafRecord(path);
                final Bytes valueBytes = leafRecord == null ? Bytes.EMPTY : leafRecord.valueBytes();
                writeEntry(writer, keyBytes, valueBytes);
                emptyFile = false;
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        if (emptyFile) {
            file.delete();
        }
    }

    /** Resolves the effective stateId, unwrapping the singleton field to its inner id (mirrors collectKeys). */
    private static int resolveStateId(@NonNull final Bytes keyBytes) {
        final ReadableSequentialData keyData = keyBytes.toReadableSequentialData();
        final int tag = keyData.readVarInt(false);
        final int stateId = tag >> TAG_FIELD_OFFSET;
        if (stateId == 1) { // singleton wrapper; real id is the next varint
            return keyData.readVarInt(false);
        }
        return stateId;
    }
}
