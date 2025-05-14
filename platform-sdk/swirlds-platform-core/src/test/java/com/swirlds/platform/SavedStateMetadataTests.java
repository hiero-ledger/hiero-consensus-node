// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.CONSENSUS_TIMESTAMP;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.HASH;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.HASH_MNEMONIC;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.LEGACY_RUNNING_EVENT_HASH;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.LEGACY_RUNNING_EVENT_HASH_MNEMONIC;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.MINIMUM_GENERATION_NON_ANCIENT;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.NODE_ID;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.NUMBER_OF_CONSENSUS_EVENTS;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.ROUND;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.SIGNING_NODES;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.SIGNING_WEIGHT_SUM;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.SOFTWARE_VERSION;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.TOTAL_WEIGHT;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.WALL_CLOCK_TIME;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import com.swirlds.platform.state.snapshot.SavedStateMetadataField;
import com.swirlds.platform.test.fixtures.roster.RosterServiceStateMock;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SignedStateMetadata Tests")
class SavedStateMetadataTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    /**
     * Serialize a metadata file then deserialize it and return the result.
     */
    private SavedStateMetadata serializeDeserialize(final SavedStateMetadata metadata) throws IOException {
        final Path path = testDirectory.resolve("metadata.txt");
        metadata.write(path);
        final SavedStateMetadata deserialized = SavedStateMetadata.parse(path);
        Files.delete(path);
        return deserialized;
    }

    /** generates a random non-negative node id. */
    private NodeId generateRandomNodeId(@NonNull final Random random) {
        Objects.requireNonNull(random, "random must not be null");
        return NodeId.of(random.nextLong(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("Random Data Test")
    void randomDataTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final long round = random.nextLong();
        final Hash hash = randomHash(random);
        final long numberOfConsensusEvents = random.nextLong();
        final Instant timestamp = RandomUtils.randomInstant(random);
        final Hash legacyRunningEventHash = randomHash(random);
        final long minimumGenerationNonAncient = random.nextLong();
        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(random.nextInt()).build();
        final Instant wallClockTime = RandomUtils.randomInstant(random);
        final NodeId nodeId = generateRandomNodeId(random);
        final List<NodeId> signingNodes = new ArrayList<>();
        for (int i = 0; i < random.nextInt(1, 10); i++) {
            signingNodes.add(generateRandomNodeId(random));
        }
        final long signingWeightSum = random.nextLong();
        final long totalWeight = random.nextLong();

        final SavedStateMetadata metadata = new SavedStateMetadata(
                round,
                hash,
                Mnemonics.generateMnemonic(hash),
                numberOfConsensusEvents,
                timestamp,
                legacyRunningEventHash,
                Mnemonics.generateMnemonic(legacyRunningEventHash),
                minimumGenerationNonAncient,
                softwareVersion.toString(),
                wallClockTime,
                nodeId,
                signingNodes,
                signingWeightSum,
                totalWeight);

        final SavedStateMetadata deserialized = serializeDeserialize(metadata);

        assertEquals(round, deserialized.round());
        assertEquals(hash, deserialized.hash());
        assertEquals(Mnemonics.generateMnemonic(hash), deserialized.hashMnemonic());
        assertEquals(numberOfConsensusEvents, deserialized.numberOfConsensusEvents());
        assertEquals(timestamp, deserialized.consensusTimestamp());
        assertEquals(legacyRunningEventHash, deserialized.legacyRunningEventHash());
        assertEquals(Mnemonics.generateMnemonic(legacyRunningEventHash), deserialized.legacyRunningEventHashMnemonic());
        assertEquals(minimumGenerationNonAncient, deserialized.minimumGenerationNonAncient());
        assertEquals(softwareVersion.toString(), deserialized.softwareVersion());
        assertEquals(wallClockTime, deserialized.wallClockTime());
        assertEquals(nodeId, deserialized.nodeId());
        assertEquals(signingNodes, deserialized.signingNodes());
        assertEquals(signingWeightSum, deserialized.signingWeightSum());
        assertEquals(totalWeight, deserialized.totalWeight());
    }

    @Test
    @DisplayName("Random Data Empty ListTest")
    void randomDataEmptyListTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final long round = random.nextLong();
        final Hash hash = randomHash(random);
        final long numberOfConsensusEvents = random.nextLong();
        final Instant timestamp = RandomUtils.randomInstant(random);
        final Hash legacyRunningEventHash = randomHash(random);
        final long minimumGenerationNonAncient = random.nextLong();
        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(random.nextInt()).build();
        final Instant wallClockTime = RandomUtils.randomInstant(random);
        final NodeId nodeId = generateRandomNodeId(random);
        final List<NodeId> signingNodes = new ArrayList<>();
        final long signingWeightSum = random.nextLong();
        final long totalWeight = random.nextLong();

        final SavedStateMetadata metadata = new SavedStateMetadata(
                round,
                hash,
                Mnemonics.generateMnemonic(hash),
                numberOfConsensusEvents,
                timestamp,
                legacyRunningEventHash,
                Mnemonics.generateMnemonic(legacyRunningEventHash),
                minimumGenerationNonAncient,
                softwareVersion.toString(),
                wallClockTime,
                nodeId,
                signingNodes,
                signingWeightSum,
                totalWeight);

        final SavedStateMetadata deserialized = serializeDeserialize(metadata);

        assertEquals(round, deserialized.round());
        assertEquals(hash, deserialized.hash());
        assertEquals(Mnemonics.generateMnemonic(hash), deserialized.hashMnemonic());
        assertEquals(numberOfConsensusEvents, deserialized.numberOfConsensusEvents());
        assertEquals(timestamp, deserialized.consensusTimestamp());
        assertEquals(minimumGenerationNonAncient, deserialized.minimumGenerationNonAncient());
        assertEquals(softwareVersion.toString(), deserialized.softwareVersion());
        assertEquals(wallClockTime, deserialized.wallClockTime());
        assertEquals(nodeId, deserialized.nodeId());
        assertEquals(signingNodes, deserialized.signingNodes());
        assertEquals(signingWeightSum, deserialized.signingWeightSum());
        assertEquals(totalWeight, deserialized.totalWeight());
    }

    @Test
    @DisplayName("Signing Nodes Sorted Test")
    void signingNodesSortedTest() {
        final Random random = getRandomPrintSeed();

        final SignedState signedState = mock(SignedState.class);
        final SigSet sigSet = mock(SigSet.class);
        final MerkleNodeState state = mock(MerkleNodeState.class);
        final TestPlatformStateFacade platformStateFacade = mock(TestPlatformStateFacade.class);
        final ConsensusSnapshot consensusSnapshot = mock(ConsensusSnapshot.class);
        when(state.getHash()).thenReturn(randomHash(random));
        final PlatformStateAccessor platformState = mock(PlatformStateAccessor.class);
        when(state.getHash()).thenReturn(randomHash(random));
        when(platformStateFacade.legacyRunningEventHashOf(state)).thenReturn(randomHash(random));
        when(platformStateFacade.consensusSnapshotOf(state)).thenReturn(consensusSnapshot);

        final Roster roster = mock(Roster.class);
        RosterServiceStateMock.setup(state, roster);

        when(signedState.getState()).thenReturn(state);
        when(signedState.getSigSet()).thenReturn(sigSet);
        when(sigSet.getSigningNodes())
                .thenReturn(new ArrayList<>(List.of(NodeId.of(3L), NodeId.of(1L), NodeId.of(2L))));

        final SavedStateMetadata metadata =
                SavedStateMetadata.create(signedState, NodeId.of(1234), Instant.now(), platformStateFacade);

        assertEquals(List.of(NodeId.of(1L), NodeId.of(2L), NodeId.of(3L)), metadata.signingNodes());
    }

    @Test
    @DisplayName("Handle Newlines Elegantly Test")
    void handleNewlinesElegantlyTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final long round = random.nextLong();
        final Hash hash = randomHash(random);
        final String hashMnemonic = Mnemonics.generateMnemonic(hash);
        final long numberOfConsensusEvents = random.nextLong();
        final Instant timestamp = RandomUtils.randomInstant(random);
        final Hash legacyRunningEventHash = randomHash(random);
        final String legacyRunningEventHashMnemonic = Mnemonics.generateMnemonic(legacyRunningEventHash);
        final long minimumGenerationNonAncient = random.nextLong();
        final Instant wallClockTime = RandomUtils.randomInstant(random);
        final NodeId nodeId = generateRandomNodeId(random);
        final List<NodeId> signingNodes = new ArrayList<>();
        for (int i = 0; i < random.nextInt(1, 10); i++) {
            signingNodes.add(generateRandomNodeId(random));
        }
        final long signingWeightSum = random.nextLong();
        final long totalWeight = random.nextLong();
        final Hash epochHash = random.nextBoolean() ? randomHash(random) : null;
        final String epochHashString = epochHash == null ? "null" : Mnemonics.generateMnemonic(epochHash);

        final SavedStateMetadata metadata = new SavedStateMetadata(
                round,
                hash,
                hashMnemonic,
                numberOfConsensusEvents,
                timestamp,
                legacyRunningEventHash,
                legacyRunningEventHashMnemonic,
                minimumGenerationNonAncient,
                "why\nare\nthere\nnewlines\nhere\nplease\nstop\n",
                wallClockTime,
                nodeId,
                signingNodes,
                signingWeightSum,
                totalWeight);

        final SavedStateMetadata deserialized = serializeDeserialize(metadata);

        assertEquals(round, deserialized.round());
        assertEquals(hash, deserialized.hash());
        assertEquals(hashMnemonic, deserialized.hashMnemonic());
        assertEquals(numberOfConsensusEvents, deserialized.numberOfConsensusEvents());
        assertEquals(timestamp, deserialized.consensusTimestamp());
        assertEquals(minimumGenerationNonAncient, deserialized.minimumGenerationNonAncient());
        assertEquals("why//are//there//newlines//here//please//stop//", deserialized.softwareVersion());
        assertEquals(wallClockTime, deserialized.wallClockTime());
        assertEquals(nodeId, deserialized.nodeId());
        assertEquals(signingNodes, deserialized.signingNodes());
        assertEquals(signingWeightSum, deserialized.signingWeightSum());
        assertEquals(totalWeight, deserialized.totalWeight());
    }

    private interface FileUpdater {
        String createNewFileString(final String fileString, SavedStateMetadata metadata) throws IOException;
    }

    private final Set<SavedStateMetadataField> requiredFields = Set.of(
            ROUND,
            NUMBER_OF_CONSENSUS_EVENTS,
            CONSENSUS_TIMESTAMP,
            MINIMUM_GENERATION_NON_ANCIENT,
            SOFTWARE_VERSION,
            WALL_CLOCK_TIME,
            NODE_ID,
            SIGNING_NODES,
            SIGNING_WEIGHT_SUM,
            TOTAL_WEIGHT,
            HASH,
            HASH_MNEMONIC);

    /**
     * Test the parsing of a mal-formatted file
     *
     * @param random        a source of randomness
     * @param fileUpdater   updates a file in some way
     * @param invalidFields the fields expected to be invalid in the mal-formatted file
     */
    private void testMalformedFile(
            final Random random, final FileUpdater fileUpdater, final Set<SavedStateMetadataField> invalidFields)
            throws IOException {

        final long round = random.nextLong();
        final Hash hash = randomHash(random);
        final long numberOfConsensusEvents = random.nextLong();
        final Instant timestamp = RandomUtils.randomInstant(random);
        final Hash legacyRunningEventHash = randomHash(random);
        final long minimumGenerationNonAncient = random.nextLong();
        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(random.nextInt()).build();
        final Instant wallClockTime = RandomUtils.randomInstant(random);
        final NodeId nodeId = generateRandomNodeId(random);
        final List<NodeId> signingNodes = new ArrayList<>();
        for (int i = 0; i < random.nextInt(1, 10); i++) {
            signingNodes.add(generateRandomNodeId(random));
        }
        final long signingWeightSum = random.nextLong();
        final long totalWeight = random.nextLong();
        final Hash epochHash = randomHash(random);

        final SavedStateMetadata metadata = new SavedStateMetadata(
                round,
                hash,
                Mnemonics.generateMnemonic(hash),
                numberOfConsensusEvents,
                timestamp,
                legacyRunningEventHash,
                Mnemonics.generateMnemonic(legacyRunningEventHash),
                minimumGenerationNonAncient,
                softwareVersion.toString(),
                wallClockTime,
                nodeId,
                signingNodes,
                signingWeightSum,
                totalWeight);

        final Path path = testDirectory.resolve("metadata.txt");
        metadata.write(path);

        final String fileString = new String(Files.readAllBytes(path));
        final String brokenString = fileUpdater.createNewFileString(fileString, metadata);
        Files.delete(path);
        if (brokenString != null) {
            Files.write(path, brokenString.getBytes());
        }

        for (final SavedStateMetadataField field : invalidFields) {
            if (requiredFields.contains(field)) {
                assertThrows(IOException.class, () -> SavedStateMetadata.parse(path));
                return;
            }
        }

        final SavedStateMetadata deserialized = SavedStateMetadata.parse(path);

        if (brokenString != null) {
            Files.delete(path);
        }

        assertEquals(round, deserialized.round());
        if (invalidFields.contains(SavedStateMetadataField.HASH)) {
            assertNull(deserialized.hash());
        } else {
            assertEquals(hash, deserialized.hash());
        }
        if (invalidFields.contains(HASH_MNEMONIC)) {
            assertNull(deserialized.hashMnemonic());
        } else {
            assertEquals(Mnemonics.generateMnemonic(hash), deserialized.hashMnemonic());
        }
        assertEquals(numberOfConsensusEvents, deserialized.numberOfConsensusEvents());
        assertEquals(timestamp, deserialized.consensusTimestamp());
        if (invalidFields.contains(LEGACY_RUNNING_EVENT_HASH)) {
            assertNull(deserialized.legacyRunningEventHash());
        } else {
            assertEquals(legacyRunningEventHash, deserialized.legacyRunningEventHash());
        }
        if (invalidFields.contains(LEGACY_RUNNING_EVENT_HASH_MNEMONIC)) {
            assertNull(deserialized.legacyRunningEventHashMnemonic());
        } else {
            assertEquals(
                    Mnemonics.generateMnemonic(legacyRunningEventHash), deserialized.legacyRunningEventHashMnemonic());
        }
        assertEquals(minimumGenerationNonAncient, deserialized.minimumGenerationNonAncient());
        assertEquals(softwareVersion.toString(), deserialized.softwareVersion());
        assertEquals(wallClockTime, deserialized.wallClockTime());
        assertEquals(nodeId, deserialized.nodeId());
        assertEquals(signingNodes, deserialized.signingNodes());
        assertEquals(signingWeightSum, deserialized.signingWeightSum());
        assertEquals(totalWeight, deserialized.totalWeight());
    }

    @Test
    @DisplayName("Empty File Test")
    void emptyFileTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final Set<SavedStateMetadataField> allFields =
                Arrays.stream(SavedStateMetadataField.values()).collect(Collectors.toSet());

        testMalformedFile(random, (s, m) -> "", allFields);
    }

    @Test
    @DisplayName("Non-Existent File Test")
    void nonExistentFileFileTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final Set<SavedStateMetadataField> allFields =
                Arrays.stream(SavedStateMetadataField.values()).collect(Collectors.toSet());

        testMalformedFile(random, (s, m) -> null, allFields);
    }

    @Test
    @DisplayName("Invalid Field Test")
    void invalidFieldTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> s.replace(SavedStateMetadataField.ROUND.toString(), "NOT_A_REAL_FIELD"),
                Set.of(SavedStateMetadataField.ROUND));
    }

    @Test
    @DisplayName("Invalid Long Test")
    void invalidLongTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> s.replace(m.nodeId().toString(), "NOT_A_REAL_LONG"),
                Set.of(SavedStateMetadataField.NODE_ID));
    }

    @Test
    @DisplayName("Invalid Instant Test")
    void invalidInstantTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> s.replace(m.wallClockTime().toString(), "NOT_A_REAL_TIME"),
                Set.of(SavedStateMetadataField.WALL_CLOCK_TIME));
    }

    @Test
    @DisplayName("Invalid Long List Test")
    void invalidLongListTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> {
                    final StringBuilder sb = new StringBuilder();

                    for (final String line : s.split("\n")) {
                        if (line.contains(SavedStateMetadataField.SIGNING_NODES.toString())) {
                            sb.append(SavedStateMetadataField.SIGNING_NODES + ": 1,2,3,4,herpderp,6,7,8\n");
                        } else {
                            sb.append(line).append("\n");
                        }
                    }

                    return sb.toString();
                },
                Set.of(SavedStateMetadataField.SIGNING_NODES));

        testMalformedFile(
                random,
                (s, m) -> {
                    final StringBuilder sb = new StringBuilder();

                    for (final String line : s.split("\n")) {
                        if (line.contains(SavedStateMetadataField.SIGNING_NODES.toString())) {
                            sb.append(SavedStateMetadataField.SIGNING_NODES + ": 1,2,3,4,,6,7,8\n");
                        } else {
                            sb.append(line).append("\n");
                        }
                    }

                    return sb.toString();
                },
                Set.of(SavedStateMetadataField.SIGNING_NODES));
    }

    @Test
    @DisplayName("Line Missing Colon Test")
    void lineMissingColonTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> s.replace(
                        SavedStateMetadataField.WALL_CLOCK_TIME + ":",
                        SavedStateMetadataField.WALL_CLOCK_TIME.toString()),
                Set.of(SavedStateMetadataField.WALL_CLOCK_TIME));
    }

    @Test
    @DisplayName("Invalid Hash Test")
    void invalidHashTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> s.replace(m.legacyRunningEventHash().toString(), "NOT_A_REAL_HASH"),
                Set.of(LEGACY_RUNNING_EVENT_HASH));
    }

    @Test
    @DisplayName("Extra Whitespace Test")
    void extraWhitespaceTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(
                random,
                (s, m) -> {
                    final StringBuilder sb = new StringBuilder();

                    for (final String line : s.split("\n")) {
                        if (line.contains(SavedStateMetadataField.WALL_CLOCK_TIME.toString())) {
                            sb.append("   \t ")
                                    .append(WALL_CLOCK_TIME)
                                    .append("  \t   :  \t    ")
                                    .append(m.wallClockTime())
                                    .append("   \t\n");
                        } else {
                            sb.append(line).append("\n");
                        }
                    }

                    return sb.toString();
                },
                Set.of());
    }

    @Test
    @DisplayName("Missing Data Test")
    void missingDataTestTest() throws IOException {
        final Random random = getRandomPrintSeed();
        testMalformedFile(random, (s, m) -> s.replace(ROUND.name(), "notARealKey"), Set.of(ROUND));
        testMalformedFile(random, (s, m) -> s.replace("\n" + HASH.name() + ":", "\nnotARealKey:"), Set.of(HASH));
        testMalformedFile(
                random, (s, m) -> s.replace("\n" + HASH_MNEMONIC.name(), "\nnotARealKey"), Set.of(HASH_MNEMONIC));
        testMalformedFile(
                random,
                (s, m) -> s.replace(NUMBER_OF_CONSENSUS_EVENTS.name(), "notARealKey"),
                Set.of(NUMBER_OF_CONSENSUS_EVENTS));
        testMalformedFile(
                random, (s, m) -> s.replace(CONSENSUS_TIMESTAMP.name(), "notARealKey"), Set.of(CONSENSUS_TIMESTAMP));
        testMalformedFile(
                random,
                (s, m) -> s.replace(LEGACY_RUNNING_EVENT_HASH.name() + ":", "notARealKey:"),
                Set.of(LEGACY_RUNNING_EVENT_HASH));
        testMalformedFile(
                random,
                (s, m) -> s.replace(LEGACY_RUNNING_EVENT_HASH_MNEMONIC.name(), "notARealKey"),
                Set.of(LEGACY_RUNNING_EVENT_HASH_MNEMONIC));
        testMalformedFile(
                random,
                (s, m) -> s.replace(MINIMUM_GENERATION_NON_ANCIENT.name(), "notARealKey"),
                Set.of(MINIMUM_GENERATION_NON_ANCIENT));
        testMalformedFile(
                random, (s, m) -> s.replace(SOFTWARE_VERSION.name(), "notARealKey"), Set.of(SOFTWARE_VERSION));
        testMalformedFile(random, (s, m) -> s.replace(WALL_CLOCK_TIME.name(), "notARealKey"), Set.of(WALL_CLOCK_TIME));
        testMalformedFile(random, (s, m) -> s.replace(NODE_ID.name(), "notARealKey"), Set.of(NODE_ID));
        testMalformedFile(random, (s, m) -> s.replace(SIGNING_NODES.name(), "notARealKey"), Set.of(SIGNING_NODES));
        testMalformedFile(
                random, (s, m) -> s.replace(SIGNING_WEIGHT_SUM.name(), "notARealKey"), Set.of(SIGNING_WEIGHT_SUM));
        testMalformedFile(random, (s, m) -> s.replace(TOTAL_WEIGHT.name(), "notARealKey"), Set.of(TOTAL_WEIGHT));
    }
}
