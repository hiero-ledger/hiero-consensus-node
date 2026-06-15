// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.sync.ReconnectTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Virtual Map Reconnect Test")
class VirtualMapReconnectTest extends VirtualMapReconnectTestBase {

    @Override
    protected VirtualDataSourceBuilder createBuilder() {
        return new InMemoryBuilder();
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.1")})
    @DisplayName("Empty teacher and empty learner")
    void emptyTeacherAndLearner() {
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.2")})
    @DisplayName("Empty teacher and full learner")
    void emptyTeacherFullLearner() {
        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        learnerMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        learnerMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        learnerMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        learnerMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        learnerMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.3")})
    @DisplayName("Full teacher and empty learner")
    void fullTeacherEmptyLearner() {
        teacherMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        teacherMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        teacherMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        teacherMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        teacherMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        teacherMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.4")})
    @DisplayName("Single-leaf teacher and empty learner")
    void singleLeafTeacherEmptyLearner() {
        teacherMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.5")})
    @DisplayName("Empty teacher and single leaf learner")
    void emptyTeacherSingleLeafLearner() {
        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.6")})
    @DisplayName("Two-leaf teacher and empty learner")
    void twoLeafTeacherEmptyLearner() {
        teacherMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.7")})
    @DisplayName("Empty teacher and two-leaf learner")
    void emptyTeacherTwoLeafLearner() {
        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.8")})
    @DisplayName("Teacher and Learner that are the same size but completely different")
    void equalSizeFullTeacherFullLearner() {
        teacherMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        teacherMap.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);
        teacherMap.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        teacherMap.put(E_KEY, EMU, TestValueCodec.INSTANCE);
        teacherMap.put(F_KEY, FOX, TestValueCodec.INSTANCE);
        teacherMap.put(G_KEY, GOOSE, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        learnerMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        learnerMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        learnerMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        learnerMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        learnerMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.9")})
    @DisplayName("Equivalent teacher and learner that are full")
    void sameSizeFullTeacherFullLearner() {
        teacherMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        teacherMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        teacherMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        teacherMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        teacherMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        teacherMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        learnerMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        learnerMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        learnerMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        learnerMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        learnerMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.10")})
    @DisplayName("Single leaf teacher and full learner where the leaf is the same")
    void singleLeafTeacherFullLearnerSameLeaf() {
        teacherMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        learnerMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        learnerMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        learnerMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        learnerMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        learnerMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.11")})
    @DisplayName("Single leaf teacher and full learner where the leaf differs")
    void singleLeafTeacherFullLearnerDifferentLeaf() {
        teacherMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        learnerMap.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        learnerMap.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        learnerMap.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        learnerMap.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        learnerMap.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.12")})
    @DisplayName("Full teacher and single-leaf learner where the leaf is equivalent")
    void fullTeacherSingleLeafLearnerSameLeaf() {
        teacherMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        teacherMap.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);
        teacherMap.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        teacherMap.put(E_KEY, EMU, TestValueCodec.INSTANCE);
        teacherMap.put(F_KEY, FOX, TestValueCodec.INSTANCE);
        teacherMap.put(G_KEY, GOOSE, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.13")})
    @DisplayName("Full teacher and single-leaf learner where the leaf differs")
    void fullTeacherSingleLeafLearnerDifferentLeaf() {
        teacherMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        teacherMap.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);
        teacherMap.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        teacherMap.put(E_KEY, EMU, TestValueCodec.INSTANCE);
        teacherMap.put(F_KEY, FOX, TestValueCodec.INSTANCE);
        teacherMap.put(G_KEY, GOOSE, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-005"), @Tag("VMAP-006")})
    @DisplayName("Reconnect aborts 5 times before success")
    void multipleAbortedReconnectsCanSucceed(int teacherStart, int teacherEnd, int learnerStart, int learnerEnd) {
        for (int i = teacherStart; i < teacherEnd; i++) {
            teacherMap.put(TestKey.longToKey(i), new TestValue(i), TestValueCodec.INSTANCE);
        }

        for (int i = learnerStart; i < learnerEnd; i++) {
            learnerMap.put(TestKey.longToKey(i), new TestValue(i), TestValueCodec.INSTANCE);
        }

        learnerBuilder.setNumCallsBeforeThrow((teacherEnd - teacherStart) / 2);
        learnerBuilder.setNumTimesToBreak(4);

        assertDoesNotThrow(() -> reconnectMultipleTimes(5), "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect")})
    void deleteAlreadyDeletedAccount() throws Exception {
        teacherMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        teacherMap.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        teacherMap.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);

        learnerMap.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        learnerMap.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        learnerMap.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE); // leaf path value is 4

        // maps / caches should be identical at this point.  But now
        // remove a key (and add another) from the teacher, before reconnect starts.
        teacherMap.remove(C_KEY);
        teacherMap.put(D_KEY, DOG, TestValueCodec.INSTANCE);

        final VirtualMap copy = teacherMap.copy();
        teacherMap.reserve();
        learnerMap.reserve();

        // reconnect happening
        VirtualMap afterMap = ReconnectTestUtils.testSynchronization(learnerMap, teacherMap, reconnectConfig);

        assertEquals(DOG, afterMap.get(D_KEY, TestValueCodec.INSTANCE), "After sync, should have D_KEY available");
        assertNull(afterMap.get(C_KEY, TestValueCodec.INSTANCE), "After sync, should not have C_KEY anymore");

        afterMap.release();
        teacherMap.release();
        learnerMap.release();
        copy.release();
    }

    @Test
    void metricsAfterReconnect() throws Exception {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        learnerMap.registerMetrics(metrics);

        Metric sizeMetric = metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, "vmap_size_state");
        assertNotNull(sizeMetric);
        assertEquals(0L, sizeMetric.get(Metric.ValueType.VALUE));

        final Bytes zeroKey = TestKey.longToKey(0);
        teacherMap.put(zeroKey, new TestValue("value0"), TestValueCodec.INSTANCE);
        learnerMap.put(zeroKey, new TestValue("value0"), TestValueCodec.INSTANCE);
        assertEquals(1L, sizeMetric.get(Metric.ValueType.VALUE));

        final Bytes key = TestKey.longToKey(123);
        teacherMap.put(key, new TestValue("value123"), TestValueCodec.INSTANCE);

        final VirtualMap teacherCopy = teacherMap.copy();
        teacherMap.reserve();
        learnerMap.reserve();

        final VirtualMap afterLearnerMap =
                ReconnectTestUtils.testSynchronization(learnerMap, teacherMap, reconnectConfig);

        final VirtualMap afterCopy = afterLearnerMap.copy();

        assertTrue(afterCopy.containsKey(key));
        assertEquals("value123", afterCopy.get(key, TestValueCodec.INSTANCE).getValue());
        assertEquals(2L, sizeMetric.get(Metric.ValueType.VALUE));

        final Bytes key2 = TestKey.longToKey(456);
        afterCopy.put(key2, new TestValue("value456"), TestValueCodec.INSTANCE);
        assertEquals(3L, sizeMetric.get(Metric.ValueType.VALUE));

        teacherCopy.release();
        teacherMap.release();
        learnerMap.release();
        afterCopy.release();
        afterLearnerMap.release();
    }

    static Stream<Arguments> provideSmallTreePermutations() {
        final List<Arguments> args = new ArrayList<>();
        // Two large leaf trees that have no intersection
        args.add(Arguments.of(0, 1_000, 1_000, 2_000));
        // Two large leaf trees that intersect
        args.add(Arguments.of(0, 1_000, 500, 1_500));
        // A smaller tree and larger tree that do not intersect
        args.add(Arguments.of(0, 10, 1_000, 2_000));
        args.add(Arguments.of(1_000, 2_000, 0, 10));
        // A smaller tree and larger tree that do intersect
        args.add(Arguments.of(0, 10, 5, 1_005));
        args.add(Arguments.of(5, 1_005, 0, 10));

        // Two hundred leaf trees that intersect
        args.add(Arguments.of(50, 250, 0, 100));
        args.add(Arguments.of(50, 249, 0, 100));
        args.add(Arguments.of(50, 251, 0, 100));
        return args.stream();
    }
}
