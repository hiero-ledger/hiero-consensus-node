// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporters;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.statevalidation.ExportCommand.MAX_OBJ_PER_FILE;
import static com.hedera.statevalidation.exporters.JsonExporter.write;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
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
import java.util.stream.LongStream;

/**
 * This class exports specified state into JSON file(s), the result is sorted by bytes representation
 */
@SuppressWarnings("rawtypes")
public class SortedJsonExporter {

    public static final String SINGLE_STATE_TMPL = "%s_%s_%d.json";

    private final Map<Integer, JsonCodec> keyCodecsById = new ConcurrentHashMap<>();
    private final Map<Integer, JsonCodec> valueCodecsById = new ConcurrentHashMap<>();

    private final File resultDir;
    private final MerkleNodeState state;
    private final String serviceName;
    private final String stateKeyName;
    private final ExecutorService executorService;
    private final int expectedStateId;

    public SortedJsonExporter(File resultDir, MerkleNodeState state, String serviceName, String stateKeyName) {
        this.resultDir = resultDir;
        this.state = state;
        this.serviceName = requireNonNull(serviceName);
        this.stateKeyName = requireNonNull(stateKeyName);
        expectedStateId = StateUtils.stateIdFor(serviceName, stateKeyName);
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm = (VirtualMap) state.getRoot();
        ArrayList<Bytes> keys = collectKeys(vm);
        if (keys.isEmpty()) {
            throw new RuntimeException(String.format("No valid keys found in state %s_%s", serviceName, stateKeyName));
        }

        // do not sort queues
        if (expectedStateId < StateKey.KeyOneOfType.RECORD_CACHE_TRANSACTION_RECEIPT_QUEUE.protoOrdinal()) {
            parallelSort(keys, Comparator.naturalOrder());
        } else {
            parallelSort(keys, (key1, key2) -> {
                try {
                    StateKey stateKey1 = StateKey.PROTOBUF.parse(key1);
                    StateKey stateKey2 = StateKey.PROTOBUF.parse(key2);
                    // queue metadata
                    if (stateKey1.key().value() instanceof SingletonType) {
                        return -1;
                    }
                    if (stateKey2.key().value() instanceof SingletonType) {
                        return 1;
                    }
                    Long index1 = (Long) stateKey1.key().value();
                    Long index2 = (Long) stateKey2.key().value();
                    return index1.compareTo(index2);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        List<CompletableFuture<Void>> futures = traverseVmInParallel(keys);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println("Export time: " + (System.currentTimeMillis() - startTimestamp) + "ms");
        executorService.close();
    }

    private ArrayList<Bytes> collectKeys(final VirtualMap vm) {
        final VirtualMapMetadata metadata = vm.getMetadata();
        final ArrayList<Bytes> keys = new ArrayList<>();
        LongStream.range(metadata.getFirstLeafPath(), metadata.getLastLeafPath() + 1)
                .forEach(path -> {
                    VirtualLeafBytes leafRecord = vm.getRecords().findLeafRecord(path);
                    final Bytes keyBytes = leafRecord.keyBytes();
                    final ReadableSequentialData keyData = keyBytes.toReadableSequentialData();
                    int tag = keyData.readVarInt(false);
                    final int actualStateId = tag >> TAG_FIELD_OFFSET;
                    if (actualStateId == 1) {
                        // it's a singleton, additional read is required
                        int singletonStateId = keyData.readVarInt(false);
                        if (singletonStateId == expectedStateId) {
                            keys.add(keyBytes);
                        }
                        return;
                    }

                    if (expectedStateId != -1 && expectedStateId != actualStateId) {
                        return;
                    }
                    keys.add(keyBytes);
                });
        return keys;
    }

    private static <T extends Comparable<? super T>> void parallelSort(ArrayList<T> list, Comparator<T> comparator) {
        // falling back to a regular parallel sort which will create a copy of the array
        T[] array = list.toArray((T[]) new Comparable[list.size()]);
        Arrays.parallelSort(array, comparator);
        for (int i = 0; i < list.size(); i++) {
            list.set(i, array[i]);
        }
    }

    private List<CompletableFuture<Void>> traverseVmInParallel(final List<Bytes> keys) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int writingParallelism = keys.size() / MAX_OBJ_PER_FILE;
        for (int i = 0; i <= writingParallelism; i++) {
            String fileName;
            fileName = String.format(SINGLE_STATE_TMPL, serviceName, stateKeyName, i + 1);
            int firstBatchIndex = i * MAX_OBJ_PER_FILE;
            int lastBatchIndex = Math.min((i + 1) * MAX_OBJ_PER_FILE, keys.size() - 1);
            futures.add(CompletableFuture.runAsync(
                    () -> processRange(keys, fileName, firstBatchIndex, lastBatchIndex), executorService));
        }
        return futures;
    }

    private void processRange(final List<Bytes> keys, String fileName, int start, int end) {
        VirtualMap vm = (VirtualMap) state.getRoot();
        File file = new File(resultDir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (int i = start; i <= end; i++) {
                final Bytes keyBytes = keys.get(i);
                final Bytes valueBytes = vm.getBytes(keyBytes);
                final StateKey stateKey;
                final StateValue stateValue;
                try {
                    stateKey = StateKey.PROTOBUF.parse(keyBytes);
                    stateValue = StateValue.PROTOBUF.parse(valueBytes);
                    if (stateKey.key().kind().equals(StateKey.KeyOneOfType.SINGLETON)) {
                        write(writer, "{\"v\":%s}\n".formatted(valueToJson(stateValue.value())));
                    } else if (stateKey.key().value() instanceof Long) { // queue
                        write(
                                writer,
                                "{\"i\":%s, \"v\":%s}\n"
                                        .formatted(stateKey.key().value(), valueToJson(stateValue.value())));
                    } else { // kv
                        write(
                                writer,
                                "{\"k\":\"%s\", \"v\":%s}\n"
                                        .formatted(keyToJson(stateKey.key()), valueToJson(stateValue.value())));
                    }
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private String keyToJson(OneOf<StateKey.KeyOneOfType> key) {
        return lookupKeyCodecFor(key).toJSON(key.value());
    }

    @SuppressWarnings("unchecked")
    private String valueToJson(OneOf<StateValue.ValueOneOfType> value) {
        return lookupValueCodecFor(value).toJSON(value.value());
    }

    private JsonCodec lookupKeyCodecFor(OneOf<StateKey.KeyOneOfType> key) {
        return keyCodecsById.computeIfAbsent(key.kind().protoOrdinal(), id -> findCodecReflectively(key.value()));
    }

    private JsonCodec lookupValueCodecFor(OneOf<StateValue.ValueOneOfType> value) {
        return valueCodecsById.computeIfAbsent(value.kind().protoOrdinal(), id -> findCodecReflectively(value.value()));
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
