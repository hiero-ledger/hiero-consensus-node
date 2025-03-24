// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.recovery.emergencyfile.EmergencyRecoveryFile;
import com.swirlds.platform.recovery.emergencyfile.Intervals;
import com.swirlds.platform.recovery.emergencyfile.Location;
import com.swirlds.platform.recovery.emergencyfile.Package;
import com.swirlds.platform.recovery.emergencyfile.Recovery;
import com.swirlds.platform.recovery.emergencyfile.State;
import com.swirlds.platform.recovery.emergencyfile.Stream;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.mockito.Mockito;

public class EmergencyRecoveryFileTests {

    private static final String FILENAME = "emergencyRecovery.yaml";
    public static final String FIELD_NAME_ROUND = "round";
    public static final String FIELD_NAME_HASH = "hash";
    public static final String FIELD_NAME_TIMESTAMP = "timestamp";
    private final StateConfig stateConfig =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(StateConfig.class);

    @TempDir
    Path tmpDir;

    @Test
    void testReadWrite() throws IOException {
        final Random r = RandomUtils.getRandomPrintSeed();
        final EmergencyRecoveryFile toWrite = createRecoveryFile(r);
        toWrite.write(tmpDir);

        final EmergencyRecoveryFile readIn = EmergencyRecoveryFile.read(stateConfig, tmpDir);

        assertNotNull(readIn, "emergency round data should not be null");
        assertEquals(toWrite.round(), readIn.round(), "round does not match");
        assertEquals(toWrite.hash(), readIn.hash(), "hash does not match");
        assertEquals(toWrite.timestamp(), readIn.timestamp(), "state timestamp does not match");
    }

    @Test
    void testReadWriteWithBootstrap() throws IOException {
        final Random r = RandomUtils.getRandomPrintSeed();
        final EmergencyRecoveryFile toWrite = createRecoveryFileWithBootstrap(r);
        toWrite.write(tmpDir);

        final EmergencyRecoveryFile readIn = EmergencyRecoveryFile.read(stateConfig, tmpDir);

        assertNotNull(readIn, "emergency round data should not be null");
        assertEquals(toWrite.round(), readIn.round(), "round does not match");
        assertEquals(toWrite.hash(), readIn.hash(), "hash does not match");
        assertEquals(toWrite.timestamp(), readIn.timestamp(), "state timestamp does not match");
        assertNotNull(readIn.recovery().bootstrap(), "bootstrap should not be null");
        assertEquals(
                toWrite.recovery().bootstrap().timestamp(),
                readIn.recovery().bootstrap().timestamp(),
                "bootstrap timestamp does not match");
    }

    @Test
    void testAdditionalValuesDontThrow() {
        final Random r = RandomUtils.getRandomPrintSeed();
        writeFile(
                Pair.of("some field", "some value"),
                Pair.of(FIELD_NAME_ROUND, randomLongString(r)),
                Pair.of(FIELD_NAME_HASH, randomHashString(r)),
                Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)),
                Pair.of("anotherOne", "anotherValue"));
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "Additional fields should not cause problems");

        writeFileWithBootstrap(
                Pair.of("some field", "some value"),
                Pair.of(FIELD_NAME_ROUND, randomLongString(r)),
                Pair.of(FIELD_NAME_HASH, randomHashString(r)),
                Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)),
                Pair.of("anotherOne", "anotherValue"));
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "Bootstrap field should not cause problems");
    }

    @Test
    void testReadFileWithFieldMissing() {
        final Random r = RandomUtils.getRandomPrintSeed();
        writeFileWithBootstrap(Pair.of(FIELD_NAME_ROUND, randomLongString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading an invalid file should throw");
        writeFile(Pair.of(FIELD_NAME_HASH, randomHashString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading an invalid file should throw");
        writeFile(Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading an invalid file should throw");
        writeFileWithBootstrap(
                Pair.of(FIELD_NAME_HASH, randomHashString(r)), Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading an invalid file should throw");
        writeFile(Pair.of(FIELD_NAME_ROUND, randomLongString(r)), Pair.of(FIELD_NAME_HASH, randomHashString(r)));
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "Reading a valid file should not throw");
        writeFileWithBootstrap(
                Pair.of(FIELD_NAME_ROUND, randomLongString(r)), Pair.of(FIELD_NAME_HASH, randomHashString(r)));
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "Reading a valid file should not throw");
        writeFile(
                Pair.of(FIELD_NAME_ROUND, randomLongString(r)), Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading an invalid file should throw");
    }

    @Test
    void testHashValueMissing() {
        final Random r = RandomUtils.getRandomPrintSeed();
        writeFile(
                // the hash label is present, but the value is missing
                Pair.of("extra", "extra"),
                Pair.of(FIELD_NAME_ROUND, randomLongString(r)),
                Pair.of(FIELD_NAME_HASH, ""),
                Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "A value missing should throw");
    }

    @Test
    void testRoundValueMissing() {
        final Random r = RandomUtils.getRandomPrintSeed();
        writeFile(
                // the round label is present, but the value is missing
                Pair.of(FIELD_NAME_ROUND, ""),
                Pair.of(FIELD_NAME_HASH, randomHashString(r)),
                Pair.of(FIELD_NAME_TIMESTAMP, randomInstantString(r)));
        assertThrows(
                IOException.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "A value missing should throw");
    }

    @Test
    void testTimestampValueMissing() {
        final Random r = RandomUtils.getRandomPrintSeed();
        writeFile(
                // the timestamp label is present, but the value is missing
                Pair.of(FIELD_NAME_ROUND, randomLongString(r)),
                Pair.of(FIELD_NAME_HASH, randomHashString(r)),
                Pair.of(FIELD_NAME_TIMESTAMP, ""));
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "An optional value missing should not throw");
    }

    @Test
    void testFileDoesNotExist() throws IOException {
        assertNull(
                EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading from a file that does not exist should return null");
    }

    @Test
    void testReadWriteLocations() throws IOException {
        final Random r = RandomUtils.getRandomPrintSeed();
        final EmergencyRecoveryFile file = new EmergencyRecoveryFile(new Recovery(
                new State(r.nextLong(), randomHash(r), Instant.now()),
                null,
                new Package(List.of(randomLocation(r), randomLocation(r), randomLocation(r))),
                null));
        file.write(tmpDir);
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "Reading a valid file should not throw");
    }

    @Test
    void testBadUrl() throws IOException {
        final URL badUrl = Mockito.mock(URL.class, Mockito.withSettings().mockMaker(MockMakers.INLINE));
        Mockito.when(badUrl.toString()).thenReturn("not a url");
        final Random r = RandomUtils.getRandomPrintSeed();
        final EmergencyRecoveryFile file = new EmergencyRecoveryFile(new Recovery(
                new State(r.nextLong(), randomHash(r), Instant.now()),
                null,
                new Package(List.of(randomLocation(r), randomLocation(r), new Location("type", badUrl, randomHash(r)))),
                null));
        file.write(tmpDir);
        assertThrows(
                Exception.class,
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir),
                "Reading a file with a bad url should throw");
    }

    @Test
    void testReadWriteStream() throws IOException {
        final Random r = RandomUtils.getRandomPrintSeed();
        final EmergencyRecoveryFile file = new EmergencyRecoveryFile(new Recovery(
                new State(r.nextLong(), randomHash(r), Instant.now()),
                null,
                null,
                new Stream(new Intervals(2000, 5000, 900000))));
        file.write(tmpDir);
        assertDoesNotThrow(
                () -> EmergencyRecoveryFile.read(stateConfig, tmpDir), "Reading a valid file should not throw");
    }

    @Test
    void testReadAllFields() throws URISyntaxException {
        final Path dir = ResourceLoader.getFile("com/swirlds/platform/recovery/emergencyfile/valid/");
        assertDoesNotThrow(() -> EmergencyRecoveryFile.read(stateConfig, dir, true));
    }

    @Test
    void testFieldMissing() throws URISyntaxException {
        final Path dir = ResourceLoader.getFile("com/swirlds/platform/recovery/emergencyfile/invalid/");
        assertThrows(Exception.class, () -> EmergencyRecoveryFile.read(stateConfig, dir, true));
    }

    private EmergencyRecoveryFile createRecoveryFile(final Random r) {
        return new EmergencyRecoveryFile(r.nextLong(), randomHash(r), Instant.now());
    }

    private EmergencyRecoveryFile createRecoveryFileWithBootstrap(final Random r) {
        final EmergencyRecoveryFile orig =
                new EmergencyRecoveryFile(r.nextLong(), randomHash(r), Instant.ofEpochMilli(r.nextLong()));
        return new EmergencyRecoveryFile(orig.recovery().state(), Instant.ofEpochMilli(r.nextLong()));
    }

    @SafeVarargs
    private void writeFile(final Pair<String, ?>... values) {
        try (final BufferedWriter file =
                new BufferedWriter(new FileWriter(tmpDir.resolve(FILENAME).toFile()))) {
            file.write("recovery:\n");
            file.write("  state:\n");
            file.write(Arrays.stream(values)
                    .map(p -> "    " + p.left() + ": " + p.right())
                    .collect(Collectors.joining("\n")));
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private void writeFileWithBootstrap(final Pair<String, ?>... values) {
        try (final BufferedWriter file =
                new BufferedWriter(new FileWriter(tmpDir.resolve(FILENAME).toFile()))) {
            file.write("recovery:\n");
            file.write("  state:\n");
            file.write(Arrays.stream(values)
                    .map(p -> "    " + p.left() + ": " + p.right())
                    .collect(Collectors.joining("\n")));
            file.write("\n  bootstrap:\n");
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Hash randomHash(final Random r) {
        final byte[] bytes = new byte[DigestType.SHA_384.digestLength()];
        r.nextBytes(bytes);
        return new Hash(bytes, DigestType.SHA_384);
    }

    private static String randomHashString(final Random r) {
        return randomHash(r).toString();
    }

    private static String randomLongString(final Random r) {
        return Long.valueOf(r.nextLong()).toString();
    }

    private static String randomInstantString(final Random r) {
        return Instant.ofEpochMilli(r.nextLong()).toString();
    }

    private static Location randomLocation(final Random r) throws MalformedURLException {
        return new Location(
                UUID.randomUUID().toString(),
                new URL(String.format("https://%s.com/", UUID.randomUUID().toString())),
                randomHash(r));
    }
}
