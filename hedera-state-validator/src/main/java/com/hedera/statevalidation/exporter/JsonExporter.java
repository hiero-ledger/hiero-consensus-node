// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.exporter;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.statevalidation.util.ConfigUtils.MAX_OBJ_PER_FILE;
import static com.hedera.statevalidation.util.ConfigUtils.PRETTY_PRINT_ENABLED;
import static java.lang.StrictMath.toIntExact;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.JsonUtils;
import com.hedera.statevalidation.util.StateUtils;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class exports the state into JSON file(s).
 */
@SuppressWarnings("rawtypes")
public class JsonExporter {

    private static final Logger log = LogManager.getLogger(JsonExporter.class);

    private static final String ALL_STATES_TMPL = "exportedState_%d.json";
    public static final String SINGLE_STATE_TMPL = "%s_%s_%d.json";

    private final File resultDir;
    private final MerkleNodeState state;
    private final String serviceName;
    private final String stateKey;
    private final ExecutorService executorService;
    private final int expectedStateId;
    private final int writingParallelism;

    private final boolean allStates;

    private final AtomicLong objectsProcessed = new AtomicLong(0);
    private long totalNumber;

    public JsonExporter(
            @NonNull final File resultDir,
            @NonNull final MerkleNodeState state,
            @Nullable final String serviceName,
            @Nullable final String stateKey) {
        this.resultDir = resultDir;
        this.state = state;
        this.serviceName = serviceName;
        this.stateKey = stateKey;

        allStates = stateKey == null;
        writingParallelism =
                toIntExact(((VirtualMap) state.getRoot()).getMetadata().getSize() / MAX_OBJ_PER_FILE) + 1;
        if (allStates) {
            expectedStateId = -1;
        } else {
            requireNonNull(serviceName);
            expectedStateId = StateUtils.stateIdFor(serviceName, stateKey);
        }
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void export() {
        final long startTimestamp = System.currentTimeMillis();
        final VirtualMap vm = (VirtualMap) state.getRoot();
        log.debug("Start exporting state");
        List<CompletableFuture<Void>> futures = traverseVmInParallel(vm);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.debug("Export time: {} seconds", (System.currentTimeMillis() - startTimestamp) / 1000);
        executorService.close();
    }

    private List<CompletableFuture<Void>> traverseVmInParallel(@NonNull final VirtualMap virtualMap) {
        VirtualMapMetadata metadata = virtualMap.getMetadata();
        totalNumber = metadata.getSize();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < writingParallelism; i++) {
            String fileName;
            if (allStates) {
                fileName = String.format(ALL_STATES_TMPL, i + 1);
            } else {
                fileName = String.format(SINGLE_STATE_TMPL, serviceName, stateKey, i + 1);
            }

            long firstPath = metadata.getFirstLeafPath() + i * MAX_OBJ_PER_FILE;
            long lastPath =
                    Math.min(metadata.getFirstLeafPath() + (i + 1) * MAX_OBJ_PER_FILE, metadata.getLastLeafPath());

            futures.add(CompletableFuture.runAsync(() -> processRange(fileName, firstPath, lastPath), executorService));
        }
        return futures;
    }

    private void processRange(@NonNull String fileName, long start, long end) {
        final VirtualMap vm = (VirtualMap) state.getRoot();
        final File file = new File(resultDir, fileName);
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
                        JsonUtils.write(
                                writer,
                                "{\"p\":%d, \"v\":%s}\n".formatted(path, StateUtils.valueToJson(stateValue.value())),
                                PRETTY_PRINT_ENABLED);
                    } else if (stateKey.key().value() instanceof Long) { // queue
                        JsonUtils.write(
                                writer,
                                "{\"p\":%d,\"i\":%s, \"v\":%s}\n"
                                        .formatted(
                                                path,
                                                stateKey.key().value(),
                                                StateUtils.valueToJson(stateValue.value())),
                                PRETTY_PRINT_ENABLED);
                    } else { // kv
                        JsonUtils.write(
                                writer,
                                "{\"p\":%d, \"k\":\"%s\", \"v\":\"%s\"}\n"
                                        .formatted(
                                                path,
                                                StateUtils.keyToJson(stateKey.key())
                                                        .replace("\\", "\\\\")
                                                        .replace("\"", "\\\""),
                                                StateUtils.valueToJson(stateValue.value())
                                                        .replace("\\", "\\\\")
                                                        .replace("\"", "\\\"")),
                                PRETTY_PRINT_ENABLED);
                    }
                    emptyFile = false;
                    long currentObjCount = objectsProcessed.incrementAndGet();
                    if (currentObjCount % MAX_OBJ_PER_FILE == 0) {
                        log.debug("{} objects of {} are processed", currentObjCount, totalNumber);
                    }
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (emptyFile) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
