// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporters;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.statevalidation.ExportCommand.MAX_OBJ_PER_FILE;
import static com.hedera.statevalidation.ExportCommand.PRETTY_PRINT_ENABLED;
import static java.lang.StrictMath.toIntExact;
import static java.util.Objects.requireNonNull;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class exports the state into JSON file(s)
 */
@SuppressWarnings("rawtypes")
public class JsonExporter {

    private static final String ALL_STATES_TMPL = "exportedState_%d.json";
    public static final String SINGLE_STATE_TMPL = "%s_%s_%d.json";

    private final Map<Integer, JsonCodec> keyCodecsById = new ConcurrentHashMap<>();
    private final Map<Integer, JsonCodec> valueCodecsById = new ConcurrentHashMap<>();

    private final File resultDir;
    private final MerkleNodeState state;
    private final String serviceName;
    private final String stateKeyName;
    private final ExecutorService executorService;
    private final int expectedStateId;
    private final int writingParallelism;

    private final boolean allStates;

    public JsonExporter(File resultDir, MerkleNodeState state, String serviceName, String stateKeyName) {
        this.resultDir = resultDir;
        this.state = state;
        this.serviceName = serviceName;
        this.stateKeyName = stateKeyName;

        allStates = stateKeyName == null;
        writingParallelism =
                toIntExact(((VirtualMap) state.getRoot()).getMetadata().getSize() / MAX_OBJ_PER_FILE) + 1;
        if (allStates) {
            expectedStateId = -1;
        } else {
            requireNonNull(serviceName);
            expectedStateId = StateUtils.stateIdFor(serviceName, stateKeyName);
        }
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm = (VirtualMap) state.getRoot();
        List<CompletableFuture<Void>> futures = traverseVmInParallel(vm);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println("Export time: " + (System.currentTimeMillis() - startTimestamp) + "ms");
        executorService.close();
    }

    private List<CompletableFuture<Void>> traverseVmInParallel(final VirtualMap virtualMap) {
        VirtualMapMetadata metadata = virtualMap.getMetadata();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < writingParallelism; i++) {
            String fileName;
            if (allStates) {
                fileName = String.format(ALL_STATES_TMPL, i + 1);
            } else {
                fileName = String.format(SINGLE_STATE_TMPL, serviceName, stateKeyName, i + 1);
            }

            long firstPath = metadata.getFirstLeafPath() + i * MAX_OBJ_PER_FILE;
            long lastPath =
                    Math.min(metadata.getFirstLeafPath() + (i + 1) * MAX_OBJ_PER_FILE, metadata.getLastLeafPath());

            futures.add(CompletableFuture.runAsync(() -> processRange(fileName, firstPath, lastPath), executorService));
        }
        return futures;
    }

    private void processRange(String fileName, long start, long end) {
        VirtualMap vm = (VirtualMap) state.getRoot();
        File file = new File(resultDir, fileName);
        boolean emptyFile = true;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (long path = start; path <= end; path++) {
                VirtualLeafBytes leafRecord = vm.getRecords().findLeafRecord(path);
                final Bytes keyBytes = leafRecord.keyBytes();
                final Bytes valueBytes = leafRecord.valueBytes();
                final StateKey stateKey;
                final StateValue stateValue;
                try {
                    final ReadableSequentialData keyData = keyBytes.toReadableSequentialData();
                    final int tag = keyData.readVarInt(false);
                    // normalize stateId for singletons
                    final int actualStateId =
                            tag >> TAG_FIELD_OFFSET == 1 ? keyData.readVarInt(false) : tag >> TAG_FIELD_OFFSET;
                    if (expectedStateId != -1 && expectedStateId != actualStateId) {
                        continue;
                    }
                    stateKey = StateKey.PROTOBUF.parse(keyBytes);
                    stateValue = StateValue.PROTOBUF.parse(valueBytes);
                    if (stateKey.key().kind().equals(StateKey.KeyOneOfType.SINGLETON)) {
                        write(writer, "{\"p\":%d, \"v\":%s}\n".formatted(path, valueToJson(stateValue.value())));
                    } else if (stateKey.key().value() instanceof Long) { // queue
                        write(
                                writer,
                                "{\"p\":%d,\"i\":%s, \"v\":%s}\n"
                                        .formatted(path, stateKey.key().value(), valueToJson(stateValue.value())));
                    } else { // kv
                        write(
                                writer,
                                "{\"p\":%d,\"k\":%s, \"v\":%s}\n"
                                        .formatted(path, keyToJson(stateKey.key()), valueToJson(stateValue.value())));
                    }
                    emptyFile = false;
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (emptyFile) {
            file.delete();
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
