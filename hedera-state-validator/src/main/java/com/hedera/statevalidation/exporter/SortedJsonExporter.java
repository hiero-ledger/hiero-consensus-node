// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporter;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.statevalidation.util.ConfigUtils.MAX_OBJ_PER_FILE;
import static com.hedera.statevalidation.util.ConfigUtils.PRETTY_PRINT_ENABLED;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.JsonUtils;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class exports a specified state into JSON file(s), the result is sorted by bytes representation.
 */
@SuppressWarnings("rawtypes")
public class SortedJsonExporter {

    private static final Logger log = LogManager.getLogger(SortedJsonExporter.class);

    public static final String SINGLE_STATE_TMPL = "%s_%s_%d.json";

    private final File resultDir;
    private final MerkleNodeState state;
    private final long suppliedFirstLeafPath;
    private final long suppliedLastLeafPath;
    private final ExecutorService executorService;
    private final Map<Integer, Set<Pair<Long, Bytes>>> keysByExpectedStateIds;
    private final Map<Integer, Pair<String, String>> nameByStateId;

    private final AtomicLong objectsProcessed = new AtomicLong(0);
    private long totalNumber;

    public SortedJsonExporter(
            @NonNull final File resultDir,
            @NonNull final MerkleNodeState state,
            @Nullable final String serviceName,
            @Nullable final String stateKey,
            long suppliedFirstLeafPath,
            long suppliedLastLeafPath) {
        this(resultDir, state, List.of(Pair.of(serviceName, stateKey)), suppliedFirstLeafPath, suppliedLastLeafPath);
    }

    public SortedJsonExporter(
            @NonNull final File resultDir,
            @NonNull final MerkleNodeState state,
            @NonNull final List<Pair<String, String>> serviceNameStateKeyList,
            long suppliedFirstLeafPath,
            long suppliedLastLeafPath) {
        this.resultDir = resultDir;
        this.state = state;
        this.keysByExpectedStateIds = new HashMap<>();
        this.nameByStateId = new HashMap<>();
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.suppliedFirstLeafPath = suppliedFirstLeafPath;
        this.suppliedLastLeafPath = suppliedLastLeafPath;

        serviceNameStateKeyList.forEach(p -> {
            final int stateId = StateUtils.stateIdFor(p.left(), p.right());
            final Comparator<Pair<Long, Bytes>> comparator;
            if (stateId < StateKey.KeyOneOfType.RECORDCACHE_I_TRANSACTION_RECEIPTS.protoOrdinal()) {
                comparator = (key1, key2) -> {
                    ReadableSequentialData keyData1 = key1.right().toReadableSequentialData();
                    keyData1.readVarInt(false); // read tag
                    keyData1.readVarInt(false); // read value

                    ReadableSequentialData keyData2 = key2.right().toReadableSequentialData();
                    keyData2.readVarInt(false); // read tag
                    keyData2.readVarInt(false); // read value

                    return keyData1.readBytes((int) keyData1.remaining())
                            .compareTo(keyData2.readBytes((int) keyData2.remaining()));
                };
            } else {
                comparator = (key1, key2) -> {
                    try {
                        final StateKey stateKey1 = StateKey.PROTOBUF.parse(key1.right());
                        final StateKey stateKey2 = StateKey.PROTOBUF.parse(key2.right());
                        // queue metadata
                        if (stateKey1.key().value() instanceof SingletonType) {
                            return -1;
                        }
                        if (stateKey2.key().value() instanceof SingletonType) {
                            return 1;
                        }
                        final Long index1 = (Long) stateKey1.key().value();
                        final Long index2 = (Long) stateKey2.key().value();
                        return index1.compareTo(index2);
                    } catch (ParseException e) {
                        throw new RuntimeException(e);
                    }
                };
            }
            keysByExpectedStateIds.computeIfAbsent(stateId, k -> new ConcurrentSkipListSet<>(comparator));
            nameByStateId.put(stateId, p);
        });
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm = (VirtualMap) state.getRoot();
        totalNumber = vm.size();
        log.debug("Collecting keys from the state...");
        collectKeys(vm);
        keysByExpectedStateIds.forEach((key, values) -> {
            if (values.isEmpty()) {
                Pair<String, String> namePair = nameByStateId.get(key);
                log.debug("No valid keys found in state {}.{}", namePair.left(), namePair.right());
            }
        });

        List<CompletableFuture<Void>> futures = traverseVmInParallel();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.debug("Export time: {} seconds.", (System.currentTimeMillis() - startTimestamp) / 1000);
        executorService.close();
    }

    private void collectKeys(@NonNull final VirtualMap vm) {
        final VirtualMapMetadata metadata = vm.getMetadata();

        // define the first path and last path
        long firstLeafPath;
        long lastLeafPath;

        if (suppliedFirstLeafPath != -1) {
            if (suppliedFirstLeafPath < metadata.getFirstLeafPath()
                    || suppliedFirstLeafPath > metadata.getLastLeafPath()) {
                throw new IllegalArgumentException("The supplied first leaf path (" + suppliedFirstLeafPath
                        + ") must be within the range of actual leaf paths in the state ["
                        + metadata.getFirstLeafPath() + ", " + metadata.getLastLeafPath() + "].");
            }
            firstLeafPath = suppliedFirstLeafPath;
        } else {
            firstLeafPath = metadata.getFirstLeafPath();
        }

        if (suppliedLastLeafPath != -1) {
            if (suppliedLastLeafPath > metadata.getLastLeafPath()
                    || suppliedLastLeafPath < metadata.getFirstLeafPath()) {
                throw new IllegalArgumentException("The supplied last leaf path (" + suppliedLastLeafPath
                        + ") must be within the range of actual leaf paths in the state ["
                        + metadata.getFirstLeafPath() + ", " + metadata.getLastLeafPath() + "].");
            }
            lastLeafPath = suppliedLastLeafPath;
        } else {
            lastLeafPath = metadata.getLastLeafPath();
        }

        LongStream.range(firstLeafPath, lastLeafPath + 1).parallel().forEach(path -> {
            final VirtualLeafBytes leafRecord = vm.getRecords().findLeafRecord(path);
            final Bytes keyBytes = leafRecord.keyBytes();
            final ReadableSequentialData keyData = keyBytes.toReadableSequentialData();
            final int tag = keyData.readVarInt(false);
            final int actualStateId = tag >> TAG_FIELD_OFFSET;
            if (actualStateId == 1) {
                // it's a singleton, additional read is required
                final int singletonStateId = keyData.readVarInt(false);
                if (keysByExpectedStateIds.containsKey(singletonStateId)) {
                    keysByExpectedStateIds.get(singletonStateId).add(Pair.of(path, keyBytes));
                }
                return;
            }
            if (keysByExpectedStateIds.containsKey(actualStateId)) {
                keysByExpectedStateIds.get(actualStateId).add(Pair.of(path, keyBytes));
            }
        });
    }

    private List<CompletableFuture<Void>> traverseVmInParallel() {
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<Integer, Set<Pair<Long, Bytes>>> entry : keysByExpectedStateIds.entrySet()) {
            final List<Pair<Long, Bytes>> keys = new ArrayList<>(entry.getValue());
            final int writingParallelism = keys.size() / MAX_OBJ_PER_FILE;
            final Pair<String, String> namePair = nameByStateId.get(entry.getKey());
            for (int i = 0; i <= writingParallelism; i++) {
                final String fileName = String.format(SINGLE_STATE_TMPL, namePair.left(), namePair.right(), i + 1);
                final int firstBatchIndex = i * MAX_OBJ_PER_FILE;
                final int lastBatchIndex = Math.min((i + 1) * MAX_OBJ_PER_FILE, keys.size() - 1);
                futures.add(CompletableFuture.runAsync(
                        () -> processRange(keys, fileName, firstBatchIndex, lastBatchIndex), executorService));
            }
        }
        return futures;
    }

    private void processRange(
            @NonNull final List<Pair<Long, Bytes>> keys, @NonNull final String fileName, int start, int end) {
        final VirtualMap vm = (VirtualMap) state.getRoot();
        final File file = new File(resultDir, fileName);
        boolean emptyFile = true;
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (int i = start; i <= end; i++) {
                final long path = keys.get(i).left();
                final Bytes keyBytes = keys.get(i).right();
                final Bytes valueBytes = vm.getRecords().findLeafRecord(path).valueBytes();
                final StateKey stateKey;
                final StateValue stateValue;
                try {
                    stateKey = StateKey.PROTOBUF.parse(keyBytes);
                    stateValue = StateValue.PROTOBUF.parse(valueBytes);
                    if (stateKey.key().kind().equals(StateKey.KeyOneOfType.SINGLETON)) {
                        JsonUtils.write(
                                writer,
                                "{\"v\":%s}\n".formatted(StateUtils.valueToJson(stateValue.value())),
                                PRETTY_PRINT_ENABLED);
                    } else if (stateKey.key().value() instanceof Long) { // queue
                        JsonUtils.write(
                                writer,
                                "{\"i\":%s, \"v\":%s}\n"
                                        .formatted(stateKey.key().value(), StateUtils.valueToJson(stateValue.value())),
                                PRETTY_PRINT_ENABLED);
                    } else { // kv
                        JsonUtils.write(
                                writer,
                                "{\"k\":\"%s\", \"v\":\"%s\"}\n"
                                        .formatted(
                                                StateUtils.keyToJson(stateKey.key())
                                                        .replace("\\", "\\\\")
                                                        .replace("\"", "\\\""),
                                                StateUtils.valueToJson(stateValue.value())
                                                        .replace("\\", "\\\\")
                                                        .replace("\"", "\\\"")),
                                PRETTY_PRINT_ENABLED);
                    }
                    emptyFile = false;
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
                long currentObjCount = objectsProcessed.incrementAndGet();
                if (currentObjCount % MAX_OBJ_PER_FILE == 0) {
                    log.debug("{} objects of {} are processed", currentObjCount, totalNumber);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (emptyFile) {
            file.delete();
        }
    }
}
