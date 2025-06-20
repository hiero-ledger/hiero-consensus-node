// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.benchmark.reconnect;

import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
public class ReconnectHalfMillionNodesBench extends VirtualMapReconnectBenchBase {

    private static final Map<TestKey, TestValue> testTeacherMap = new HashMap<>();
    private static final Map<TestKey, TestValue> testLearnerMap = new HashMap<>();

    static {
        try {
            VirtualMapReconnectBenchBase.startup();
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        // Create a state to be reused in every run
        StateBuilder.buildState(new Random(9823452658L), 500_000, 0.15, 0.15, testTeacherMap::put, testLearnerMap::put);
    }

    @Setup(Level.Invocation)
    @Override
    public void setupEach() {
        super.setupEach();

        testTeacherMap.entrySet().forEach(e -> teacherMap.put(e.getKey(), e.getValue()));
        testLearnerMap.entrySet().forEach(e -> learnerMap.put(e.getKey(), e.getValue()));
    }

    @Benchmark
    public void reconnectHalfMillionNodes() throws Exception {
        super.reconnect();
    }
}
