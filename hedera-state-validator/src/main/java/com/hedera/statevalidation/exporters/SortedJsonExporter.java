// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporters;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.statevalidation.ExportCommand.MAX_OBJ_PER_FILE;
import static com.hedera.statevalidation.exporters.JsonExporter.write;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
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
    private final ExecutorService executorService;
    private final Map<Integer, Set<Pair<Long, Bytes>>> keysByExpectedStateIds;
    private final Map<Integer, Pair<String, String>> nameByStateId;

    private final AtomicLong objectsProcessed = new AtomicLong(0);
    private long totalNumber;

    public SortedJsonExporter(File resultDir, MerkleNodeState state, String serviceName, String stateKeyName) {
        this(resultDir, state, List.of(Pair.of(serviceName, stateKeyName)));
    }

    public SortedJsonExporter(
            File resultDir, MerkleNodeState state, List<Pair<String, String>> serviceNameStateKeyList) {
        this.resultDir = resultDir;
        this.state = state;
        keysByExpectedStateIds = new HashMap<>();
        nameByStateId = new HashMap<>();
        serviceNameStateKeyList.forEach(p -> {
            int stateId = StateUtils.stateIdFor(p.left(), p.right());
            final Comparator<Pair<Long, Bytes>> comparator;
            if (stateId < StateKey.KeyOneOfType.RECORDCACHE_I_TRANSACTIONRECEIPTQUEUE.protoOrdinal()) {
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
                        StateKey stateKey1 = StateKey.PROTOBUF.parse(key1.right());
                        StateKey stateKey2 = StateKey.PROTOBUF.parse(key2.right());
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
                };
            }
            keysByExpectedStateIds.computeIfAbsent(stateId, k -> new ConcurrentSkipListSet<>(comparator));
            nameByStateId.put(stateId, p);
        });
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm = (VirtualMap) state.getRoot();
        totalNumber = vm.size();
        System.out.println("Collecting keys from the state...");
        collectKeys(vm);
        keysByExpectedStateIds.forEach((key, values) -> {
            if (values.isEmpty()) {
                Pair<String, String> namePair = nameByStateId.get(key);
                System.out.printf("No valid keys found in state %s_%s%n", namePair.left(), namePair.right());
            }
        });

        List<CompletableFuture<Void>> futures = traverseVmInParallel();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println("Export time: " + ((System.currentTimeMillis() - startTimestamp) / 1000) + " seconds");
        executorService.close();
    }

    private void collectKeys(final VirtualMap vm) {
        final VirtualMapMetadata metadata = vm.getMetadata();
        LongStream.range(metadata.getFirstLeafPath(), metadata.getLastLeafPath() + 1)
                .parallel()
                .forEach(path -> {
                    VirtualLeafBytes leafRecord = vm.getRecords().findLeafRecord(path);
                    final Bytes keyBytes = leafRecord.keyBytes();
                    final ReadableSequentialData keyData = keyBytes.toReadableSequentialData();
                    int tag = keyData.readVarInt(false);
                    final int actualStateId = tag >> TAG_FIELD_OFFSET;
                    if (actualStateId == 1) {
                        // it's a singleton, additional read is required
                        int singletonStateId = keyData.readVarInt(false);
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
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<Integer, Set<Pair<Long, Bytes>>> entry : keysByExpectedStateIds.entrySet()) {
            final List<Pair<Long, Bytes>> keys = new ArrayList<>(entry.getValue());
            final int writingParallelism = keys.size() / MAX_OBJ_PER_FILE;
            final Pair<String, String> namePair = nameByStateId.get(entry.getKey());
            for (int i = 0; i <= writingParallelism; i++) {
                String fileName = String.format(SINGLE_STATE_TMPL, namePair.left(), namePair.right(), i + 1);
                int firstBatchIndex = i * MAX_OBJ_PER_FILE;
                int lastBatchIndex = Math.min((i + 1) * MAX_OBJ_PER_FILE, keys.size() - 1);
                futures.add(CompletableFuture.runAsync(
                        () -> processRange(keys, fileName, firstBatchIndex, lastBatchIndex), executorService));
            }
        }
        return futures;
    }

    private void processRange(final List<Pair<Long, Bytes>> keys, String fileName, int start, int end) {
        VirtualMap vm = (VirtualMap) state.getRoot();
        File file = new File(resultDir, fileName);
        boolean emptyFile = true;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
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
                        write(writer, "{\"v\":%s}\n".formatted(valueToJson(stateValue.value())));
                    } else if (stateKey.key().value() instanceof Long) { // queue
                        write(
                                writer,
                                "{\"i\":%s, \"v\":%s}\n"
                                        .formatted(stateKey.key().value(), valueToJson(stateValue.value())));
                    } else { // kv
                        write(
                                writer,
                                "{\"k\":\"%s\", \"v\":\"%s\"}\n"
                                        .formatted(
                                                keyToJson(stateKey.key())
                                                        .replace("\\", "\\\\")
                                                        .replace("\"", "\\\""),
                                                valueToJson(stateValue.value())
                                                        .replace("\\", "\\\\")
                                                        .replace("\"", "\\\"")));
                    }
                    emptyFile = false;
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
                long currentObjCount = objectsProcessed.incrementAndGet();
                if (currentObjCount % MAX_OBJ_PER_FILE == 0) {
                    System.out.printf("%s objects of %s are processed\n", currentObjCount, totalNumber);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (emptyFile) {
            file.delete();
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
