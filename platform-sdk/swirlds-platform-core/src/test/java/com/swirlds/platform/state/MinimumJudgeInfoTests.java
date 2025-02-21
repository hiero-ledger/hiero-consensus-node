// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.platform.state.MinimumJudgeInfo.MAX_MINIMUM_JUDGE_INFO_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MinimumJudgeInfoTests {

    @Test
    void emptySerializationTest() throws IOException {
        final List<MinimumJudgeInfo> original = List.of();

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);

        MinimumJudgeInfo.serializeList(original, out);

        final SerializableDataInputStream in =
                new SerializableDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
        final List<MinimumJudgeInfo> deserialized = MinimumJudgeInfo.deserializeList(in);

        assertEquals(original, deserialized);
    }

    @Test
    void serializationTest() throws IOException {
        final Random random = getRandomPrintSeed();
        final int size = random.nextInt(1, MAX_MINIMUM_JUDGE_INFO_SIZE);

        final List<MinimumJudgeInfo> original = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            original.add(new MinimumJudgeInfo(random.nextLong(), random.nextLong()));
        }

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);

        MinimumJudgeInfo.serializeList(original, out);

        final SerializableDataInputStream in =
                new SerializableDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
        final List<MinimumJudgeInfo> deserialized = MinimumJudgeInfo.deserializeList(in);

        assertEquals(original, deserialized);
    }

    @Test
    void serializationOverflowTest() throws IOException {
        final Random random = getRandomPrintSeed();
        final int size = MAX_MINIMUM_JUDGE_INFO_SIZE + random.nextInt(1, MAX_MINIMUM_JUDGE_INFO_SIZE);

        final List<MinimumJudgeInfo> original = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            original.add(new MinimumJudgeInfo(random.nextLong(), random.nextLong()));
        }

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);

        MinimumJudgeInfo.serializeList(original, out);

        final SerializableDataInputStream in =
                new SerializableDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        assertThrows(IOException.class, () -> MinimumJudgeInfo.deserializeList(in));
    }
}
