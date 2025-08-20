// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporters;

import static com.hedera.statevalidation.ExportCommand.MAX_OBJ_PER_FILE;
import static com.hedera.statevalidation.ExportCommand.PRETTY_PRINT_ENABLED;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.JsonCodec;
import com.swirlds.common.utility.Labeled;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

/**
 * This class exports specified state into JSON file(s), the result is sorted by bytes representation
 */
@SuppressWarnings("rawtypes")
public class SortedJsonExporter {

    public static final String SINGLE_STATE_TMPL = "%s_%s_%d.json";

    private final Map<String, JsonCodec> keyCodecsByName = new ConcurrentHashMap<>();
    private final Map<String, JsonCodec> valueCodecsByName = new ConcurrentHashMap<>();

    private final File resultDir;
    private final MerkleStateRoot state;
    private final String serviceName;
    private final String stateKeyName;
    private final ExecutorService executorService;

    public SortedJsonExporter(File resultDir, MerkleStateRoot state, String serviceName, String stateKeyName) {
        this.resultDir = resultDir;
        this.state = state;
        this.serviceName = requireNonNull(serviceName);
        this.stateKeyName = requireNonNull(stateKeyName);
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();

        for (int i = 0; i < state.getNumberOfChildren(); i++) {
            if (state.getChild(i) instanceof Labeled labeled
                    && labeled.getLabel().equals(StateMetadata.computeLabel(serviceName, stateKeyName))) {
                switch (labeled) {
                    case SingletonNode<?> singletonNode -> processForSingleton(singletonNode);
                    case QueueNode<?> queueNode -> processForQueue(queueNode);
                    case VirtualMap virtualMap -> processForVirtualMap(virtualMap);
                    default -> throw new IllegalStateException("Expecting SingletonNode, QueueNode or VirtualMap");
                }
            }
        }

        System.out.println("Export time: " + (System.currentTimeMillis() - startTimestamp) + "ms");
        executorService.close();
    }

    private void processForSingleton(SingletonNode<?> singletonNode) {
        final String fileName = String.format(SINGLE_STATE_TMPL, serviceName, stateKeyName, 1);
        File file = new File(resultDir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            write(writer, "{\"v\":%s}\n".formatted(singletonToJson(singletonNode)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void processForQueue(QueueNode<?> queueNode) {
        final String fileName = String.format(SINGLE_STATE_TMPL, serviceName, stateKeyName, 1);

        File file = new File(resultDir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            AtomicInteger index = new AtomicInteger(1);
            queueNode.iterator().forEachRemaining(queueItem -> {
                try {
                    write(
                            writer,
                            "{\"i\":%s, \"v\":%s}\n"
                                    .formatted(
                                            index.getAndIncrement(), queueItemToJson(queueNode.getLabel(), queueItem)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void processForVirtualMap(VirtualMap<OnDiskKey, OnDiskValue> vm) {
        ArrayList<OnDiskKey> keys = collectKeys(vm);
        if (keys.isEmpty()) {
            throw new RuntimeException(String.format("No valid keys found in state %s_%s", serviceName, stateKeyName));
        }
        List<CompletableFuture<Void>> futures = traverseVmInParallel(vm, keys);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private ArrayList<OnDiskKey> collectKeys(final VirtualMap vm) {
        final VirtualRootNode<OnDiskKey, OnDiskValue> virtualRootNode = vm.getRight();
        final var ds = virtualRootNode.getDataSource();

        final Codec<Object> codec;
        try {
            VirtualLeafRecord<OnDiskKey, OnDiskValue> firstEntry =
                    virtualRootNode.getRecords().findLeafRecord(ds.getFirstLeafPath(), false);
            Object protoObject = firstEntry.getKey().getKey();
            Field codecField = protoObject.getClass().getDeclaredField("PROTOBUF");
            codec = (Codec<Object>) codecField.get(null);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        final ArrayList<OnDiskKey> keys = new ArrayList<>();
        LongStream.range(ds.getFirstLeafPath(), ds.getLastLeafPath() + 1).forEach(path -> {
            VirtualLeafRecord<OnDiskKey, OnDiskValue> leafRecord =
                    virtualRootNode.getRecords().findLeafRecord(path, false);
            keys.add(leafRecord.getKey());
        });
        parallelSort(keys, Comparator.comparing(k -> codec.toBytes(k.getKey())));
        return keys;
    }

    private static void parallelSort(ArrayList<OnDiskKey> list, Comparator<OnDiskKey> comparator) {
        // falling back to a regular parallel sort which will create a copy of the array
        OnDiskKey[] array = list.toArray(new OnDiskKey[0]);
        Arrays.parallelSort(array, comparator);
        for (int i = 0; i < list.size(); i++) {
            list.set(i, array[i]);
        }
    }

    private List<CompletableFuture<Void>> traverseVmInParallel(
            final VirtualMap<OnDiskKey, OnDiskValue> vm, final List<OnDiskKey> keys) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int writingParallelism = keys.size() / MAX_OBJ_PER_FILE;
        for (int i = 0; i <= writingParallelism; i++) {
            String fileName;
            fileName = String.format(SINGLE_STATE_TMPL, serviceName, stateKeyName, i + 1);
            int firstBatchIndex = i * MAX_OBJ_PER_FILE;
            int lastBatchIndex = Math.min((i + 1) * MAX_OBJ_PER_FILE, keys.size() - 1);
            futures.add(CompletableFuture.runAsync(
                    () -> processRange(vm, keys, fileName, firstBatchIndex, lastBatchIndex), executorService));
        }
        return futures;
    }

    private void processRange(
            final VirtualMap<OnDiskKey, OnDiskValue> vm,
            final List<OnDiskKey> keys,
            String fileName,
            int start,
            int end) {
        File file = new File(resultDir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (int i = start; i <= end; i++) {
                final OnDiskKey key = keys.get(i);
                final OnDiskValue value = vm.get(key);
                write(
                        writer,
                        "{\"k\":\"%s\", \"v\":\"%s\"}\n"
                                .formatted(
                                        keyToJson(vm.getLabel(), key.getKey())
                                                .replace("\\", "\\\\")
                                                .replace("\"", "\\\""),
                                        valueToJson(vm.getLabel(), value.getValue())
                                                .replace("\\", "\\\\")
                                                .replace("\"", "\\\"")));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void write(BufferedWriter writer, String value) throws IOException {
        if (PRETTY_PRINT_ENABLED) {
            writer.write(value);
        } else {
            writer.write(value.replaceAll("[\\p{C}\\s]", ""));
            writer.newLine();
        }
    }

    @SuppressWarnings("unchecked")
    private String singletonToJson(SingletonNode<?> singletonNode) {
        return lookupValueCodecFor(singletonNode.getLabel(), singletonNode.getValue())
                .toJSON(singletonNode.getValue());
    }

    @SuppressWarnings("unchecked")
    private String queueItemToJson(String label, Object protoObject) {
        return lookupValueCodecFor(label, protoObject).toJSON(protoObject);
    }

    @SuppressWarnings("unchecked")
    private String keyToJson(String name, Object key) {
        return lookupKeyCodecFor(name, key).toJSON(key);
    }

    @SuppressWarnings("unchecked")
    private String valueToJson(String name, Object value) {
        return lookupValueCodecFor(name, value).toJSON(value);
    }

    private JsonCodec lookupKeyCodecFor(String name, Object key) {
        return keyCodecsByName.computeIfAbsent(name, id -> findCodecReflectively(key));
    }

    private JsonCodec lookupValueCodecFor(String name, Object value) {
        return valueCodecsByName.computeIfAbsent(name, id -> findCodecReflectively(value));
    }

    @SuppressWarnings("rawtypes")
    private JsonCodec findCodecReflectively(Object protoObject) {
        try {
            Field jsonField = protoObject.getClass().getDeclaredField("JSON");
            return (JsonCodec) jsonField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
