// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.io.streams.AugmentedDataOutputStream;
import com.swirlds.common.io.streams.SerializableDataInputStreamImpl;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.test.fixtures.io.SelfSerializableExample;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Serializable Stream Tests")
public class SerializableStreamTests {
    static final String PACKAGE_PREFIX = "com.swirlds.common.io";
    private static final int MAX_LENGTH = 1000;
    private static final int LENGTH_IN_BYTES = 4;
    private static final int CHECKSUM_IN_BYTES = 4;
    private static final Random random = new Random();

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();

        registry.registerConstructables(PACKAGE_PREFIX);
        registry.registerConstructables("com.swirlds.common.merkle.utility");

        registry.registerConstructable(
                new ClassConstructorPair(SelfSerializableExample.class, SelfSerializableExample::new));
    }

    static Stream<byte[]> byteArrayArgProvider() {
        return Stream.of(null, new byte[0], new byte[] {1, 2, 3}, "some string".getBytes());
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Int Array")
    void intArray() throws IOException {

        // Test null array
        int[] data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeIntArray(data);
        io.startReading();
        int[] resultingData = io.getInput().readIntArray(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new int[] {1, 2, 3};
        io = new InputOutputStream();
        io.getOutput().writeIntArray(data);
        io.startReading();
        resultingData = io.getInput().readIntArray(Integer.MAX_VALUE);
        assertEquals(data.length, resultingData.length);
        for (int index = 0; index < resultingData.length; index++) {
            assertEquals(data[index], resultingData[index]);
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeIntArray(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readIntArray(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Int List")
    void intList() throws IOException {

        // Test null array
        List<Integer> data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeIntList(data);
        io.startReading();
        List<Integer> resultingData = io.getInput().readIntList(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new LinkedList<>();
        data.add(1);
        data.add(2);
        data.add(3);
        io = new InputOutputStream();
        io.getOutput().writeIntList(data);
        io.startReading();
        resultingData = io.getInput().readIntList(Integer.MAX_VALUE);
        assertEquals(data.size(), resultingData.size());
        for (int index = 0; index < resultingData.size(); index++) {
            assertEquals(data.get(index), resultingData.get(index));
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeIntList(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readIntArray(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Long Array")
    void longArray() throws IOException {

        // Test null array
        long[] data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeLongArray(data);
        io.startReading();
        long[] resultingData = io.getInput().readLongArray(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new long[] {1, 2, 3};
        io = new InputOutputStream();
        io.getOutput().writeLongArray(data);
        io.startReading();
        resultingData = io.getInput().readLongArray(Integer.MAX_VALUE);
        assertEquals(data.length, resultingData.length);
        for (int index = 0; index < resultingData.length; index++) {
            assertEquals(data[index], resultingData[index]);
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeLongArray(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readLongArray(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Long List")
    void longList() throws IOException {

        // Test null array
        List<Long> data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeLongList(data);
        io.startReading();
        List<Long> resultingData = io.getInput().readLongList(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new LinkedList<>();
        data.add(1L);
        data.add(2L);
        data.add(3L);
        io = new InputOutputStream();
        io.getOutput().writeLongList(data);
        io.startReading();
        resultingData = io.getInput().readLongList(Integer.MAX_VALUE);
        assertEquals(data.size(), resultingData.size());
        for (int index = 0; index < resultingData.size(); index++) {
            assertEquals(data.get(index), resultingData.get(index));
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeLongList(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readLongList(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Float Array")
    void floatArray() throws IOException {

        // Test null array
        float[] data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeFloatArray(data);
        io.startReading();
        float[] resultingData = io.getInput().readFloatArray(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new float[] {1, 2, 3};
        io = new InputOutputStream();
        io.getOutput().writeFloatArray(data);
        io.startReading();
        resultingData = io.getInput().readFloatArray(Integer.MAX_VALUE);
        assertEquals(data.length, resultingData.length);
        for (int index = 0; index < resultingData.length; index++) {
            assertEquals(data[index], resultingData[index]);
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeFloatArray(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readFloatArray(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Float List")
    void floatList() throws IOException {

        // Test null array
        List<Float> data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeFloatList(data);
        io.startReading();
        List<Float> resultingData = io.getInput().readFloatList(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new LinkedList<>();
        data.add(1.0F);
        data.add(2.0F);
        data.add(3.0F);
        io = new InputOutputStream();
        io.getOutput().writeFloatList(data);
        io.startReading();
        resultingData = io.getInput().readFloatList(Integer.MAX_VALUE);
        assertEquals(data.size(), resultingData.size());
        for (int index = 0; index < resultingData.size(); index++) {
            assertEquals(data.get(index), resultingData.get(index));
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeFloatList(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readFloatList(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Double Array")
    void doubleArray() throws IOException {

        // Test null array
        double[] data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeDoubleArray(data);
        io.startReading();
        double[] resultingData = io.getInput().readDoubleArray(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new double[] {1, 2, 3};
        io = new InputOutputStream();
        io.getOutput().writeDoubleArray(data);
        io.startReading();
        resultingData = io.getInput().readDoubleArray(Integer.MAX_VALUE);
        assertEquals(data.length, resultingData.length);
        for (int index = 0; index < resultingData.length; index++) {
            assertEquals(data[index], resultingData[index]);
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeDoubleArray(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readDoubleArray(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Double List")
    void doubleList() throws IOException {

        // Test null array
        List<Double> data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeDoubleList(data);
        io.startReading();
        List<Double> resultingData = io.getInput().readDoubleList(Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new LinkedList<>();
        data.add(1.0);
        data.add(2.0);
        data.add(3.0);
        io = new InputOutputStream();
        io.getOutput().writeDoubleList(data);
        io.startReading();
        resultingData = io.getInput().readDoubleList(Integer.MAX_VALUE);
        assertEquals(data.size(), resultingData.size());
        for (int index = 0; index < resultingData.size(); index++) {
            assertEquals(data.get(index), resultingData.get(index));
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeDoubleList(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readDoubleList(2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("String Array")
    void stringArray() throws IOException {

        // Test null array
        String[] data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeStringArray(data);
        io.startReading();
        String[] resultingData = io.getInput().readStringArray(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new String[] {"this", "is", "a", "test"};
        io = new InputOutputStream();
        io.getOutput().writeStringArray(data);
        io.startReading();
        resultingData = io.getInput().readStringArray(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(data.length, resultingData.length);
        for (int index = 0; index < resultingData.length; index++) {
            assertEquals(data[index], resultingData[index]);
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeStringArray(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readStringArray(2, Integer.MAX_VALUE));

        io = new InputOutputStream();
        io.getOutput().writeStringArray(data);
        io.startReading();
        final SerializableDataInputStream in2 = io.getInput();
        assertThrows(IOException.class, () -> in2.readStringArray(Integer.MAX_VALUE, 2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("String List")
    void stringList() throws IOException {

        // Test null array
        List<String> data = null;
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeStringList(data);
        io.startReading();
        List<String> resultingData = io.getInput().readStringList(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(resultingData, null);

        // Test regular array
        data = new LinkedList<>();
        data.add("this");
        data.add("is");
        data.add("a");
        data.add("test");
        io = new InputOutputStream();
        io.getOutput().writeStringList(data);
        io.startReading();
        resultingData = io.getInput().readStringList(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(data.size(), resultingData.size());
        for (int index = 0; index < resultingData.size(); index++) {
            assertEquals(data.get(index), resultingData.get(index));
        }

        // Test data that is too long
        io = new InputOutputStream();
        io.getOutput().writeStringList(data);
        io.startReading();
        final SerializableDataInputStream in = io.getInput();
        assertThrows(IOException.class, () -> in.readStringList(2, Integer.MAX_VALUE));

        io = new InputOutputStream();
        io.getOutput().writeStringList(data);
        io.startReading();
        final SerializableDataInputStream in2 = io.getInput();
        assertThrows(IOException.class, () -> in2.readStringList(Integer.MAX_VALUE, 2));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("String normalization")
    void stringNormalization() throws IOException {
        final Charset charset = StandardCharsets.UTF_8;
        // the last character can be represented in different ways in UTF-8
        final String originalString = "Lazar Petrović";
        final byte[] nfd =
                Normalizer.normalize(originalString, Normalizer.Form.NFD).getBytes(charset);
        final byte[] nfc =
                Normalizer.normalize(originalString, Normalizer.Form.NFC).getBytes(charset);
        assertFalse(Arrays.equals(nfd, nfc), "this string should have different byte representations");

        final InputOutputStream io = new InputOutputStream();
        io.getOutput().writeNormalisedString(new String(nfd, charset));
        io.getOutput().writeNormalisedString(new String(nfc, charset));
        io.startReading();
        final String desNfd = io.getInput().readNormalisedString(originalString.getBytes().length * 2);
        final String desNfc = io.getInput().readNormalisedString(originalString.getBytes().length * 2);

        assertEquals(desNfd, desNfc, "when serialized and deserialized, the strings should have been normalized");
        assertArrayEquals(
                desNfd.getBytes(charset),
                desNfc.getBytes(charset),
                "if the strings were normalized, they should have identical byte representations");
    }

    @ParameterizedTest
    @MethodSource("byteArrayArgProvider")
    @Tag(TestComponentTags.IO)
    @DisplayName("Byte Array")
    void byteArray(byte[] array) throws IOException {
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeByteArray(array);
        io.startReading();
        byte[] copy = io.getInput().readByteArray(MAX_LENGTH);

        assertArrayEquals(array, copy);
        io.close();
    }

    static Stream<SelfSerializableExample> serializableArgProvider() {
        return Stream.of(null, new SelfSerializableExample(), new SelfSerializableExample(123, "some string"));
    }

    @ParameterizedTest
    @MethodSource("serializableArgProvider")
    @Tag(TestComponentTags.IO)
    @DisplayName("Serializable")
    void serializable(SelfSerializableExample serializable) throws IOException {
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializable(serializable, true);
        io.getOutput().writeSerializable(serializable, true);
        io.getOutput().writeSerializable(serializable, false);
        io.startReading();
        SelfSerializable runtime = io.getInput().readSerializable();
        SelfSerializable withId = io.getInput().readSerializable(true, SelfSerializableExample::new);
        SelfSerializable withoutId = io.getInput().readSerializable(false, SelfSerializableExample::new);

        assertEquals(serializable, runtime);
        assertEquals(serializable, withId);
        assertEquals(serializable, withoutId);
        io.close();
    }

    static Stream<String> stringArgProvider() {
        return Stream.of(
                null, "", "some string", "Лазар Петровић" // multi byte UTF-8
                );
    }

    @ParameterizedTest
    @MethodSource("stringArgProvider")
    @Tag(TestComponentTags.IO)
    @DisplayName("String")
    void string(String string) throws IOException {
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeNormalisedString(string);
        io.startReading();
        String copy = io.getInput().readNormalisedString(MAX_LENGTH);

        assertEquals(string, copy);
        io.close();
    }

    static Stream<List<SelfSerializableExample>> serializableListArgProvider() {
        return Stream.of(
                null,
                Collections.EMPTY_LIST,
                Arrays.asList(null, new SelfSerializableExample(), new SelfSerializableExample(321, "aaddssff")),
                Arrays.asList(
                        new SelfSerializableExample(),
                        new SelfSerializableExample(55446, "another string"),
                        null,
                        null));
    }

    @ParameterizedTest
    @MethodSource("serializableListArgProvider")
    @Tag(TestComponentTags.IO)
    @DisplayName("Serializable List")
    void serializableList(List<SelfSerializableExample> list) throws IOException {
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializableList(list, true, true);
        io.getOutput().writeSerializableList(list, true, true);
        io.getOutput().writeSerializableList(list, false, true);
        io.getOutput().writeSerializableList(list, true, false);
        io.getOutput().writeSerializableList(list, false, false);
        io.startReading();
        List<SelfSerializable> runtime = io.getInput().readSerializableList(MAX_LENGTH);
        List<SelfSerializableExample> allSameWithClassId =
                io.getInput().readSerializableList(MAX_LENGTH, true, SelfSerializableExample::new);
        List<SelfSerializableExample> allSameWithoutClassId =
                io.getInput().readSerializableList(MAX_LENGTH, false, SelfSerializableExample::new);
        List<SelfSerializableExample> notAllSameWithClassId =
                io.getInput().readSerializableList(MAX_LENGTH, true, SelfSerializableExample::new);
        List<SelfSerializableExample> notAllSameWithoutClassId =
                io.getInput().readSerializableList(MAX_LENGTH, false, SelfSerializableExample::new);

        assertEquals(list, runtime);
        assertEquals(list, allSameWithClassId);
        assertEquals(list, allSameWithoutClassId);
        assertEquals(list, notAllSameWithClassId);
        assertEquals(list, notAllSameWithoutClassId);

        io.close();
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("Instant Test")
    void instantTest() throws IOException {
        InputOutputStream io = new InputOutputStream();

        Instant original = Instant.now();
        io.getOutput().writeInstant(original);
        // fake instant with bad nanos
        io.getOutput().writeLong(123);
        io.getOutput().writeLong(-100);
        // fake instant with bad nanos
        io.getOutput().writeLong(123);
        io.getOutput().writeLong(1_000_000_000);

        io.startReading();

        Instant copy = io.getInput().readInstant();
        assertEquals(original, copy);
        // these should throw because nanos is invalid
        assertThrows(IOException.class, () -> io.getInput().readInstant());
        assertThrows(IOException.class, () -> io.getInput().readInstant());
    }

    @Tag(TestComponentTags.IO)
    @DisplayName("getLongArraySerializedLengthTest")
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 1024})
    void getLongArraySerializedLengthTest(int num) {
        assertEquals(
                LENGTH_IN_BYTES + num * Long.BYTES,
                AugmentedDataOutputStream.getArraySerializedLength(new long[num]),
                "length mismatch");

        long[] numbers = null;
        if (num > 0) {
            numbers = new long[num];
            for (int i = 0; i < numbers.length; i++) {
                numbers[i] = random.nextLong();
            }
        }
    }

    @Tag(TestComponentTags.IO)
    @DisplayName("getIntArraySerializedLengthTest")
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 1024})
    void getIntArraySerializedLengthTest(int num) {
        assertEquals(
                LENGTH_IN_BYTES + num * Integer.BYTES,
                AugmentedDataOutputStream.getArraySerializedLength(new int[num]));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("getNullByteArraySerializedLengthTest")
    void getNullByteArraySerializedLengthTest() {

        // without checksum
        assertEquals(LENGTH_IN_BYTES, AugmentedDataOutputStream.getArraySerializedLength(null, false));

        // with checksum
        assertEquals(
                LENGTH_IN_BYTES + CHECKSUM_IN_BYTES, AugmentedDataOutputStream.getArraySerializedLength(null, true));
    }

    @Tag(TestComponentTags.IO)
    @DisplayName("getByteArraySerializedLengthTest")
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 1024})
    void getByteArraySerializedLengthTest(int num) {
        assertEquals(
                LENGTH_IN_BYTES + num * Byte.BYTES, AugmentedDataOutputStream.getArraySerializedLength(new byte[num]));

        // without checksum
        assertEquals(
                LENGTH_IN_BYTES + num * Byte.BYTES,
                AugmentedDataOutputStream.getArraySerializedLength(new byte[num], false));

        // with checksum
        assertEquals(
                LENGTH_IN_BYTES + CHECKSUM_IN_BYTES + num * Byte.BYTES,
                AugmentedDataOutputStream.getArraySerializedLength(new byte[num], true));
    }

    @Test
    @Tag(TestComponentTags.IO)
    @DisplayName("serializedLengthNullArray")
    void serializedLengthNullArray() throws IOException {
        final int length = SerializableDataOutputStreamImpl.getSerializedLength(null, true, false);

        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (final SerializableDataOutputStreamImpl dos = new SerializableDataOutputStreamImpl(bos)) {
                dos.writeSerializableArray(null, true, false);
                checkExpectedSize(dos.size(), length);
            }
        }
    }

    private void checkExpectedSize(int actualWrittenBytes, int calculatedBytes) {
        assertEquals(actualWrittenBytes, calculatedBytes, "length mismatch");
    }

    /**
     * Tests class ID restrictions for {@link SerializableDataInputStream#readSerializable(Set)}
     */
    @Test
    void testRestrictedReadSerializable() throws IOException {
        final Random random = getRandomPrintSeed();

        final SerializableLong data = new SerializableLong(random.nextLong());

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(byteOut);

        out.writeSerializable(data, true);
        final byte[] bytes = byteOut.toByteArray();

        // Should work if the class id is not restricted
        final SerializableDataInputStream in1 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final SerializableLong deserialized1 = in1.readSerializable(null);
        assertEquals(data, deserialized1);
        assertNotSame(data, deserialized1);

        // Should not work if the class id is restricted to other classIDs
        final SerializableDataInputStream in2 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        assertThrows(IOException.class, () -> in2.readSerializable(Set.of(1L, 2L, 3L, 4L)));

        // Should work if class ID is in the restricted set
        final SerializableDataInputStream in3 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final SerializableLong deserialized3 = in3.readSerializable(Set.of(1L, 2L, 3L, 4L, SerializableLong.CLASS_ID));
        assertEquals(data, deserialized3);
        assertNotSame(data, deserialized3);
    }

    /**
     * Tests class ID restrictions for
     * {@link SerializableDataInputStream#readSerializableIterableWithSize(int, boolean, Supplier, Consumer, Set)}.
     */
    @Test
    void testRestrictedReadSerializableIterableWithSize() throws IOException {
        final Random random = getRandomPrintSeed();

        final List<SerializableLong> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(new SerializableLong(random.nextLong()));
        }

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(byteOut);

        out.writeSerializableIterableWithSize(data.iterator(), data.size(), true, false);
        final byte[] bytes = byteOut.toByteArray();

        // Should work if the class id is not restricted
        final SerializableDataInputStream in1 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final List<SerializableLong> deserialized1 = new ArrayList<>();
        in1.readSerializableIterableWithSize(data.size(), x -> deserialized1.add((SerializableLong) x), null);
        assertEquals(data, deserialized1);

        // Should not work if the class id is restricted to other classIDs
        final SerializableDataInputStream in2 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        assertThrows(
                IOException.class,
                () -> in2.readSerializableIterableWithSize(data.size(), x -> {}, Set.of(1L, 2L, 3L, 4L)));

        // Should work if class ID is in the restricted set
        final SerializableDataInputStream in3 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final List<SerializableLong> deserialized3 = new ArrayList<>();
        in3.readSerializableIterableWithSize(
                data.size(),
                x -> deserialized3.add((SerializableLong) x),
                Set.of(1L, 2L, 3L, 4L, SerializableLong.CLASS_ID));
        assertEquals(data, deserialized3);
    }

    @Test
    void testRestrictedReadSerializableList() throws IOException {
        final Random random = getRandomPrintSeed();

        final List<SerializableLong> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(new SerializableLong(random.nextLong()));
        }

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(byteOut);

        out.writeSerializableList(data, true, false);
        final byte[] bytes = byteOut.toByteArray();

        // Should work if the class id is not restricted
        final SerializableDataInputStream in1 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final List<SerializableLong> deserialized1 = in1.readSerializableList(data.size(), null);
        assertEquals(data, deserialized1);

        // Should not work if the class id is restricted to other classIDs
        final SerializableDataInputStream in2 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        assertThrows(IOException.class, () -> in2.readSerializableList(data.size(), Set.of(1L, 2L, 3L, 4L)));

        // Should work if class ID is in the restricted set
        final SerializableDataInputStream in3 = new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final List<SerializableLong> deserialized3 =
                in3.readSerializableList(data.size(), Set.of(1L, 2L, 3L, 4L, SerializableLong.CLASS_ID));
        assertEquals(data, deserialized3);
    }

    @Test
    void testRestrictedReadSerializableArray() throws IOException {
        final Random random = getRandomPrintSeed();

        final SerializableLong[] data = new SerializableLong[10];
        for (int i = 0; i < 10; i++) {
            data[i] = new SerializableLong(random.nextLong());
        }

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStreamImpl out = new SerializableDataOutputStreamImpl(byteOut);

        out.writeSerializableArray(data, true, false);
        final byte[] bytes = byteOut.toByteArray();

        // Should work if the class id is not restricted
        final SerializableDataInputStreamImpl in1 =
                new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final SerializableLong[] deserialized1 =
                in1.readSerializableArray(SerializableLong[]::new, data.length, true, (Set<Long>) null);
        assertArrayEquals(data, deserialized1);

        // Should not work if the class id is restricted to other classIDs
        final SerializableDataInputStreamImpl in2 =
                new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        assertThrows(
                IOException.class,
                () -> in2.readSerializableArray(SerializableLong[]::new, data.length, true, Set.of(1L, 2L, 3L, 4L)));

        // Should work if class ID is in the restricted set
        final SerializableDataInputStreamImpl in3 =
                new SerializableDataInputStreamImpl(new ByteArrayInputStream(bytes));
        final SerializableLong[] deserialized3 = in3.readSerializableArray(
                SerializableLong[]::new, data.length, true, Set.of(1L, 2L, 3L, 4L, SerializableLong.CLASS_ID));
        assertArrayEquals(data, deserialized3);
    }
}
