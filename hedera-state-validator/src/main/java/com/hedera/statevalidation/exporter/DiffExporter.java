// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporter;

import static com.swirlds.state.merkle.StateKeyUtils.extractStateIdFromStateKeyOneOf;

import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.ParallelProcessingUtils;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports the differences between two states to JSON files. This class iterates over all the keys in the first state,
 * collects the entries that are absent in the second state (modifications and deletions), then iterates over the second state
 * and collects the entries that are absent in the first state (additions). Then the collected results are sorted by key
 * and written to JSON files.
 */
public class DiffExporter {

    private static final String STATE_1_DIFF_JSON = "state1-diff.json";
    private static final String STATE_2_DIFF_JSON = "state2-diff.json";
    private final File resultDir;
    private final VirtualMapState state1;
    private final VirtualMapState state2;
    private final int expectedStateId;
    private final AtomicLong objectsProcessed = new AtomicLong(0);

    public DiffExporter(
            @NonNull final File resultDir,
            @NonNull final VirtualMapState state1,
            @NonNull final VirtualMapState state2,
            @Nullable final String serviceName,
            @Nullable final String stateKey) {
        this.resultDir = resultDir;
        this.state1 = state1;
        this.state2 = state2;
        if (stateKey == null) {
            expectedStateId = -1;
        } else {
            Objects.requireNonNull(serviceName);
            expectedStateId = StateUtils.stateIdFor(serviceName, stateKey);
        }
    }

    /**
     * Exports the differences between two states to JSON files.
     */
    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm1 = state1.getRoot();
        final VirtualMap vm2 = state2.getRoot();

        System.out.println("Start comparing states");

        final List<DiffEntry> state1Entries = Collections.synchronizedList(new ArrayList<>());
        final List<DiffEntry> state2Entries = Collections.synchronizedList(new ArrayList<>());

        // Traverse the first state (old)
        final CompletableFuture<Void> firstRunFuture =
                CompletableFuture.runAsync(() -> traverseAndCompare(vm1, vm2, true, state1Entries, state2Entries));
        // Traverse the second state (ne)
        final CompletableFuture<Void> secondRunFuture =
                CompletableFuture.runAsync(() -> traverseAndCompare(vm2, vm1, false, state1Entries, state2Entries));
        CompletableFuture.allOf(firstRunFuture, secondRunFuture).join();

        state1Entries.sort(Comparator.comparing(e -> e.keyBytes));
        state2Entries.sort(Comparator.comparing(e -> e.keyBytes));

        createOutputFile(state1Entries, STATE_1_DIFF_JSON);
        createOutputFile(state2Entries, STATE_2_DIFF_JSON);

        System.out.printf("Diff time: %d seconds%n", (System.currentTimeMillis() - startTimestamp) / 1000);
    }

    private void createOutputFile(List<DiffEntry> diffEntries, String fileName) {
        final File resultFile = new File(resultDir.getParent(), fileName);
        try (BufferedWriter writer1 = new BufferedWriter(new FileWriter(resultFile))) {
            for (DiffEntry e : diffEntries) {
                String record = buildDiffRecord(e.path, e.keyBytes, e.valueBytes);
                writer1.write(record);
                writer1.newLine();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Traverses the source virtual map and compares its entries with the target virtual map.
     * Collects the differences between the two states and populates the provided lists with the results.
     *
     * @param vmSource source VirtualMap
     * @param vmTarget target VirtualMap
     * @param isFirstPass whether this is the first pass of the traversal
     * @param state1Entries list to collect entries absent in the second state
     * @param state2Entries list to collect entries absent in the first state
     */
    private void traverseAndCompare(
            @NonNull final VirtualMap vmSource,
            @NonNull final VirtualMap vmTarget,
            boolean isFirstPass,
            @NonNull final List<DiffEntry> state1Entries,
            @NonNull final List<DiffEntry> state2Entries) {
        final VirtualMapMetadata metadata = vmSource.getMetadata();
        final long firstLeafPath = metadata.getFirstLeafPath();
        final long lastLeafPath = metadata.getLastLeafPath();

        ParallelProcessingUtils.processRange(firstLeafPath, lastLeafPath + 1, path -> {
                    final long currentCount = objectsProcessed.incrementAndGet();
                    if (currentCount % 1_000_000 == 0) {
                        System.out.println("Objects processed: " + currentCount);
                    }

                    VirtualLeafBytes<?> sourceLeaf = null;
                    try {
                        sourceLeaf = vmSource.getRecords().findLeafRecord(path);
                    } catch (final Exception e) {
                        System.err.println(
                                "Unexpected error while finding leaf record by path in source: " + e.getMessage());
                    }
                    if (sourceLeaf == null) {
                        return;
                    }

                    final Bytes keyBytes = sourceLeaf.keyBytes();
                    final Bytes sourceValueBytes = sourceLeaf.valueBytes();

                    try {
                        if (expectedStateId != -1) {
                            final int actualStateId = extractStateIdFromStateKeyOneOf(keyBytes);
                            if (expectedStateId != actualStateId) {
                                return;
                            }
                        }

                        // Check in target map
                        final VirtualLeafBytes<?> targetLeaf =
                                vmTarget.getRecords().findLeafRecord(keyBytes);

                        if (isFirstPass) {
                            if (targetLeaf == null) {
                                // Deletion: present in old, absent in new
                                state1Entries.add(new DiffEntry(path, keyBytes, sourceValueBytes));
                            } else {
                                final Bytes targetValueBytes = targetLeaf.valueBytes();
                                if (!Objects.equals(sourceValueBytes, targetValueBytes)) {
                                    state1Entries.add(new DiffEntry(path, keyBytes, sourceValueBytes));
                                    final long targetPath = targetLeaf.path();
                                    state2Entries.add(new DiffEntry(targetPath, keyBytes, targetValueBytes));
                                }
                                // Equal: skip
                            }
                        } else {
                            if (targetLeaf == null) {
                                // Addition: present in new, absent in old
                                state2Entries.add(new DiffEntry(path, keyBytes, sourceValueBytes));
                            }
                        }
                    } catch (Exception e) { // Catch ParseException, IOException, etc.
                        throw new RuntimeException(e);
                    }
                })
                .join();
    }

    private String buildDiffRecord(long path, final Bytes keyBytes, final Bytes valueBytes)
            throws Exception { // Adjust for ParseException, IOException, etc.
        final StateKey stateKey = StateKey.PROTOBUF.parse(keyBytes);
        final StateValue stateValue = StateValue.PROTOBUF.parse(valueBytes);

        final String record;
        if (stateKey.key().kind().equals(StateKey.KeyOneOfType.SINGLETON)) {
            record = "{\"p\":%d, \"v\":%s}"
                    .formatted(path, StateUtils.valueToJson(stateValue.value()).replace("\n", ""));
        } else if (stateKey.key().value() instanceof Long) { // queue
            record = "{\"p\":%d, \"i\":%s, \"v\":%s}"
                    .formatted(
                            path,
                            stateKey.key().value(),
                            StateUtils.valueToJson(stateValue.value()).replace("\n", ""));
        } else { // kv
            record = "{\"p\":%d, \"k\":\"%s\", \"v\":\"%s\"}"
                    .formatted(
                            path,
                            StateUtils.keyToJson(stateKey.key())
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", ""),
                            StateUtils.valueToJson(stateValue.value())
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", ""));
        }
        return record;
    }

    private record DiffEntry(long path, Bytes keyBytes, Bytes valueBytes) {}
}
