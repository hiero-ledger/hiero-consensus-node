// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporters;

import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.hapi.platform.state.VirtualMapValue;
import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.StrictMath.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * This class exports the state into JSON file(s)
 */
@SuppressWarnings("rawtypes")
public class JsonExporter {

    private static final int ITEMS_PER_FILE = Integer.parseInt(System.getProperty("itemsPerFile", "1000000"));
    private static final String ALL_STATES_TMPL = "exportedState_%d.json";
    public static final String SPECIFIC_STATE_TMPL = "%s_%s.json";

    private final JsonCodec[] keyCodecs = new JsonCodec[10011]; // see max ordinal of VirtualMapKey
    private final JsonCodec[] valueCodecs = new JsonCodec[10011]; // see max ordinal of VirtualMapValue

    private final MerkleNodeState state;
    private final String outputFileName;
    private final ExecutorService executorService;
    private final int expectedStateId;
    private final int writingParallelism;

    private final CountDownLatch startProcessing = new CountDownLatch(1);
    private final boolean allStates;

    public JsonExporter(MerkleNodeState state, String serviceName, String stateName) {
        this.state = state;
        int numberOfFiles = toIntExact(((VirtualMap) state.getRoot()).getState().getSize() / ITEMS_PER_FILE) + 1;
        allStates = stateName == null;
        if(!allStates) {
            requireNonNull(serviceName);
        }
        outputFileName = allStates ? ALL_STATES_TMPL : SPECIFIC_STATE_TMPL.formatted(serviceName, stateName);
        expectedStateId = allStates ? -1 : StateUtils.stateIdFor(serviceName, stateName);
        writingParallelism = allStates ? numberOfFiles : 1;
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
        VirtualMapMetadata metadata = virtualMap.getState();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        if (allStates) {
            for (int i = 0; i < writingParallelism; i++) {
                long firstPath = metadata.getFirstLeafPath() + i * ITEMS_PER_FILE;
                long lastPath = Math.min(metadata.getFirstLeafPath() + (i + 1) * ITEMS_PER_FILE, metadata.getLastLeafPath() + 1);
                String fileName = outputFileName.formatted(i + 1);
                futures.add(CompletableFuture.runAsync(() -> processRange(fileName, firstPath, lastPath), executorService));
            }
        } else {
            String fileName = outputFileName;
            futures.add(CompletableFuture.runAsync(() -> processRange(fileName, metadata.getFirstLeafPath(), metadata.getLastLeafPath() + 1), executorService));
        }
        return futures;
    }

    private void processRange(String fileName, long start, long end) {
        VirtualMap vm = (VirtualMap) state.getRoot();
        File file = new File(System.getProperty("state.dir"), fileName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (long path = start; path < end; path++) {
                VirtualLeafBytes leafRecord = vm.getRecords().findLeafRecord(path);
                final Bytes keyBytes = leafRecord.keyBytes();
                final Bytes valueBytes = leafRecord.valueBytes();
                final VirtualMapKey virtualMapKey;
                final VirtualMapValue virtualMapValue;
                try {
                    virtualMapKey = VirtualMapKey.PROTOBUF.parse(keyBytes);
                    if (expectedStateId != -1
                            && expectedStateId != virtualMapKey.key().kind().protoOrdinal()) {
                        continue;
                    }
                    virtualMapValue = VirtualMapValue.PROTOBUF.parse(valueBytes);
                    if (virtualMapKey.key().kind().equals(VirtualMapKey.KeyOneOfType.SINGLETON)) {
                        writer.write("{\"p\":%d, \"v\":%s}\n".formatted(path, valueToJson(virtualMapValue.value())));
                    } else if (virtualMapKey.key().value() instanceof Long) { // queue
                        writer.write("{\"p\":%d,\"i\":%s, \"v\":%s}\n"
                                .formatted(path, virtualMapKey.key().value(), valueToJson(virtualMapValue.value())));
                    } else { // kv
                        writer.write("{\"p\":%d,\"k\":%s, \"v\":%s}\n"
                                .formatted(path, keyToJson(virtualMapKey.key()), valueToJson(virtualMapValue.value())));
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
    private String keyToJson(OneOf<VirtualMapKey.KeyOneOfType> key) {
        return lookupKeyCodecFor(key).toJSON(key.value());
    }

    @SuppressWarnings("unchecked")
    private String valueToJson(OneOf<VirtualMapValue.ValueOneOfType> value) {
        return lookupValueCodecFor(value).toJSON(value.value());
    }

    private JsonCodec lookupKeyCodecFor(OneOf<VirtualMapKey.KeyOneOfType> key) {
        JsonCodec codec = keyCodecs[key.kind().protoOrdinal()];
        if (codec != null) {
            return codec;
        } else {
            JsonCodec lookedUpCodec = findCodecReflectively(key.value());
            keyCodecs[key.kind().protoOrdinal()] = lookedUpCodec;
            return lookedUpCodec;
        }
    }

    private JsonCodec lookupValueCodecFor(OneOf<VirtualMapValue.ValueOneOfType> value) {
        JsonCodec codec = valueCodecs[value.kind().protoOrdinal()];
        if (codec != null) {
            return codec;
        } else {
            JsonCodec lookedUpCodec = findCodecReflectively(value.value());
            valueCodecs[value.kind().protoOrdinal()] = lookedUpCodec;
            return lookedUpCodec;
        }
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