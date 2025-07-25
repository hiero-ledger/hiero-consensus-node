// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
    void equalFullTeacherFullLearner() {
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
    void singleLeafTeacherFullLearner() {
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
    void singleLeafTeacherFullLearner2() {
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
    void fullTeacherSingleLeafLearner() {
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
    void fullTeacherSingleLeafLearner2() {
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

        final MerkleInternal teacherTree = createTreeForMap(teacherMap);
        final VirtualMap copy = teacherMap.copy();
        final MerkleInternal learnerTree = createTreeForMap(learnerMap);

        // reconnect happening
        DummyMerkleInternal afterSyncLearnerTree =
                MerkleTestUtils.hashAndTestSynchronization(learnerTree, teacherTree, reconnectConfig);

        // not sure what is the better way to get the embedded Virtual map
        DummyMerkleInternal node = afterSyncLearnerTree.getChild(1);
        VirtualMap afterMap = node.getChild(3);

        assertEquals(DOG, afterMap.get(D_KEY, TestValueCodec.INSTANCE), "After sync, should have D_KEY available");
        assertNull(afterMap.get(C_KEY, TestValueCodec.INSTANCE), "After sync, should not have C_KEY anymore");

        afterSyncLearnerTree.release();
        copy.release();
        teacherTree.release();
        learnerTree.release();
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
