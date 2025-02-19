/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.virtualmap.benchmark.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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

    private static final Map<Bytes, TestValue> testTeacherMap = new HashMap<>();
    private static final Map<Bytes, TestValue> testLearnerMap = new HashMap<>();

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

        testTeacherMap.entrySet().forEach(e -> teacherMap.put(e.getKey(), e.getValue(), TestValueCodec.INSTANCE));
        testLearnerMap.entrySet().forEach(e -> learnerMap.put(e.getKey(), e.getValue(), TestValueCodec.INSTANCE));
    }

    @Benchmark
    public void reconnectHalfMillionNodes() throws Exception {
        super.reconnect();
    }
}
