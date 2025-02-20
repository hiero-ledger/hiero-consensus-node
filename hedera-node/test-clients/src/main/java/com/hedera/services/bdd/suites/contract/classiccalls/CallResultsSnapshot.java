// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the saving and loading of call result snapshots.
 */
public enum CallResultsSnapshot {
    CALL_RESULTS_SNAPSHOT;

    private static final String CALL_RESULT_KEY = "callResults";
    private static final String STATIC_CALL_RESULT_KEY = "staticCallResults";
    private static final String SNAPSHOT_LOC = "hedera-node/test-clients/src/main/resources/call-results-snapshot.json";
    private static final Map<String, Map<ClassicFailureMode, FailableCallResult>> CALL_RESULTS =
            new ConcurrentHashMap<>();
    private static final Map<String, Map<ClassicFailureMode, FailableStaticCallResult>> STATIC_CALL_RESULTS =
            new ConcurrentHashMap<>();

    public void begin() {
        CALL_RESULTS.clear();
        STATIC_CALL_RESULTS.clear();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        CALL_RESULTS.clear();
        STATIC_CALL_RESULTS.clear();
        final var reader = new ObjectMapper().reader();
        final Map<String, Map<String, Map<String, Map<String, Object>>>> snapshot;
        try {
            snapshot = reader.readValue(Files.newInputStream(Paths.get(SNAPSHOT_LOC)), Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loadCallResultsFrom(snapshot);
        loadStaticCallResultsFrom(snapshot);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void commit() {
        final var writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
        final Map<String, Map<String, Map<ClassicFailureMode, Object>>> snapshot = new HashMap<>();
        snapshot.put(CALL_RESULT_KEY, (Map) CALL_RESULTS);
        snapshot.put(STATIC_CALL_RESULT_KEY, (Map) STATIC_CALL_RESULTS);
        try {
            writer.writeValue(Files.newOutputStream(Paths.get(SNAPSHOT_LOC)), snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void recordResult(final String callName, final ClassicFailureMode mode, final FailableCallResult result) {
        CALL_RESULTS.computeIfAbsent(callName, ignore -> new HashMap<>()).put(mode, result);
    }

    public void recordStaticResult(
            final String callName, final ClassicFailureMode mode, final FailableStaticCallResult result) {
        STATIC_CALL_RESULTS.computeIfAbsent(callName, ignore -> new HashMap<>()).put(mode, result);
    }

    public FailableCallResult expectedResultOf(final String callName, final ClassicFailureMode mode) {
        return CALL_RESULTS.get(callName).get(mode);
    }

    public FailableStaticCallResult expectedStaticCallResultOf(final String callName, final ClassicFailureMode mode) {
        return STATIC_CALL_RESULTS.get(callName).get(mode);
    }

    private static void loadCallResultsFrom(Map<String, Map<String, Map<String, Map<String, Object>>>> snapshot) {
        snapshot.get(CALL_RESULT_KEY).forEach((callName, callResult) -> {
            final Map<ClassicFailureMode, FailableCallResult> failureResults = new EnumMap<>(ClassicFailureMode.class);
            callResult.forEach((failureMode, resultMap) -> {
                final String maybeChildStatus = (String) resultMap.get("childStatus");
                final var result = new FailableCallResult(
                        ResponseCodeEnum.valueOf((String) resultMap.get("topLevelStatus")),
                        (String) resultMap.get("topLevelErrorMessage"),
                        maybeChildStatus == null ? null : ResponseCodeEnum.valueOf(maybeChildStatus),
                        (String) resultMap.get("childErrorMessage"));
                failureResults.put(ClassicFailureMode.valueOf(failureMode), result);
            });
            CALL_RESULTS.put(callName, failureResults);
        });
    }

    private static void loadStaticCallResultsFrom(Map<String, Map<String, Map<String, Map<String, Object>>>> snapshot) {
        snapshot.get(STATIC_CALL_RESULT_KEY).forEach((callName, callResult) -> {
            final Map<ClassicFailureMode, FailableStaticCallResult> failureResults =
                    new EnumMap<>(ClassicFailureMode.class);
            callResult.forEach((failureMode, resultMap) -> {
                final var result = new FailableStaticCallResult(
                        ResponseCodeEnum.valueOf((String) resultMap.get("status")),
                        (String) resultMap.get("errorMessage"));
                failureResults.put(ClassicFailureMode.valueOf(failureMode), result);
            });
            STATIC_CALL_RESULTS.put(callName, failureResults);
        });
    }
}
