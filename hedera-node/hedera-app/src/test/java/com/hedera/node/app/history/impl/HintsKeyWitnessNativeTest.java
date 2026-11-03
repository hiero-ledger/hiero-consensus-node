// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashInternalNode;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;
import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.blocks.impl.BlockRootTree;
import com.hedera.node.app.blocks.impl.BlockRootTreeHasher;
import com.hedera.node.app.blocks.impl.StreamingBlockRootTreeHasher;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.history.impl.HintsKeyProvenanceVerifier.Checkpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Native hinTS vectors with real preprocessing, weighted signing, aggregation, and verification across key rotations. */
class HintsKeyWitnessNativeTest {
    private static final long JANUARY_2025 = 1_735_689_600L;
    private static final int NUM_PARTIES = 4;
    private static final HintsLibraryImpl LIBRARY = new HintsLibraryImpl();
    private static final HintsKeyWitnessVerifier WITNESS_VERIFIER = new HintsKeyWitnessVerifier(LIBRARY);
    private static final HintsKeyProvenanceVerifier BATCH_VERIFIER = new HintsKeyProvenanceVerifier(WITNESS_VERIFIER);

    private static Bytes crs;
    private static List<Generation> generations;
    private static List<HintsKeyProvenance> chain;
    private static HintsKeyProvenance abandonedCertificate;
    private static HintsKeyWitness firstWitness;
    private static HintsKeyWitness finalWitness;
    private static Checkpoint initialCheckpoint;

    private record Generation(long id, Bytes verificationKey, Bytes aggregationKey, Map<Integer, Bytes> privateKeys) {}

    @BeforeAll
    static void createNativeRotations() {
        final var initialCrs = LIBRARY.newCrs((short) NUM_PARTIES);
        crs = decodeCrsUpdate(initialCrs.length(), LIBRARY.updateCrs(initialCrs, seed(71)))
                .crs();
        generations = List.of(generation(1), generation(2), generation(3), generation(4), generation(5));
        chain = List.of(
                certificate(generations.get(0), generations.get(1)),
                certificate(generations.get(1), generations.get(2)),
                certificate(generations.get(2), generations.get(3)));
        abandonedCertificate = certificate(generations.get(0), generations.get(4));
        firstWitness = witness(generations.get(1), JANUARY_2025 + 86_400, BlockHashAlgorithm.SHA2_256);
        finalWitness = witness(generations.get(3), JANUARY_2025 + 3 * 86_400, BlockHashAlgorithm.SHA2_384);
        initialCheckpoint = new Checkpoint(generations.getFirst().verificationKey(), new Timestamp(JANUARY_2025, 0));
    }

    @Test
    void catchesUpAcrossCommittedBatchesAndSkipsAnIntermediateWithoutAWitness() {
        final var firstCheckpoint = BATCH_VERIFIER
                .verifyBatch(initialCheckpoint, chain.subList(0, 1), firstWitness, 1)
                .orElseThrow();
        assertEquals(generations.get(1).verificationKey(), firstCheckpoint.verificationKey());
        // Generation 3 has no retained witness; it can still be an interior link of the second bounded batch.
        final var finalCheckpoint = BATCH_VERIFIER
                .verifyBatch(firstCheckpoint, chain.subList(1, 3), finalWitness, 2)
                .orElseThrow();
        assertEquals(generations.get(3).verificationKey(), finalCheckpoint.verificationKey());
        assertEquals(finalWitness.timestamp(), finalCheckpoint.witnessedAt());
        assertTrue(BATCH_VERIFIER
                .verifyBatch(initialCheckpoint, chain, finalWitness, 2)
                .isEmpty());
        assertTrue(BATCH_VERIFIER
                .verifyBatch(firstCheckpoint, chain.subList(1, 3), finalWitness, 1)
                .isEmpty());
    }

    @Test
    void abandonedCandidateCertificateCannotCommitAnAnchorWithoutItsSigningWitness() {
        assertTrue(WITNESS_VERIFIER.verifyCertificate(abandonedCertificate, initialCheckpoint.verificationKey()));
        final var original = initialCheckpoint;
        assertTrue(BATCH_VERIFIER
                .verifyBatch(original, List.of(abandonedCertificate), null, 1)
                .isEmpty());
        final var wrongKeyWitness = firstWitness
                .copyBuilder()
                .targetHintsConstructionId(generations.get(4).id())
                .build();
        assertTrue(BATCH_VERIFIER
                .verifyBatch(original, List.of(abandonedCertificate), wrongKeyWitness, 1)
                .isEmpty());
        assertEquals(initialCheckpoint, original);
        assertNotEquals(abandonedCertificate.targetVerificationKey(), original.verificationKey());
    }

    @Test
    void rejectsTamperedTimestampSiblingSignatureAndDigestAndWrongLeafPosition() {
        final var generation = generations.get(1);
        final var changedTimestamp = firstWitness
                .copyBuilder()
                .timestamp(new Timestamp(firstWitness.timestampOrThrow().seconds() + 1, 123))
                .build();
        final var changedSibling = firstWitness
                .copyBuilder()
                .siblingHash(flip(firstWitness.siblingHash()))
                .build();
        final var changedSignature = firstWitness
                .copyBuilder()
                .signature(flip(firstWitness.signature()))
                .build();
        final var changedDigest = firstWitness
                .copyBuilder()
                .hashAlgorithm(BlockHashAlgorithm.SHA2_384)
                .build();
        for (final var tampered : List.of(changedTimestamp, changedSibling, changedSignature, changedDigest)) {
            assertFalse(WITNESS_VERIFIER.verify(tampered, generation.verificationKey()));
        }

        final var digest = DigestType.SHA_256.buildDigest();
        final var timestampLeaf = hashLeaf(digest, Timestamp.PROTOBUF.toBytes(firstWitness.timestampOrThrow()));
        final var backwardsRoot = hashInternalNode(digest, firstWitness.siblingHash(), timestampLeaf);
        final var backwardsProof = firstWitness
                .copyBuilder()
                .signature(sign(generation, backwardsRoot, List.of(0, 1)))
                .build();
        assertFalse(WITNESS_VERIFIER.verify(backwardsProof, generation.verificationKey()));
        assertFalse(WITNESS_VERIFIER.verify(firstWitness, generations.get(0).verificationKey()));
    }

    @Test
    void requiresStrictlyMoreThanOneThirdSigningWeight() {
        final var endpoint = generations.get(1);
        final var oneThirdSignature = sign(endpoint, HintsKeyWitnessVerifier.rootHash(firstWitness), List.of(0));
        assertFalse(WITNESS_VERIFIER.verify(
                firstWitness.copyBuilder().signature(oneThirdSignature).build(), endpoint.verificationKey()));
        assertTrue(WITNESS_VERIFIER.verify(firstWitness, endpoint.verificationKey()));
    }

    @Test
    void historicalCatchupDoesNotRelaxMessageFreshnessOrAllowCheckpointReplay() {
        final var checkpoint = BATCH_VERIFIER
                .verifyBatch(initialCheckpoint, chain, finalWitness, 3)
                .orElseThrow();
        final var now = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(asInstant(checkpoint.witnessedAt()).isBefore(now.minusSeconds(60)));
        // A message consumer independently applies its own freshness policy after verifying its message proof.
        assertFalse(freshMessageProof(finalWitness, checkpoint, now, 60));
        final var freshProof =
                witness(generations.get(3), now.minusSeconds(1).getEpochSecond(), BlockHashAlgorithm.SHA2_256);
        assertTrue(freshMessageProof(freshProof, checkpoint, now, 60));

        assertTrue(
                BATCH_VERIFIER.verifyBatch(checkpoint, chain, finalWitness, 3).isEmpty());
        final var timestampAlreadyCommitted =
                new Checkpoint(initialCheckpoint.verificationKey(), finalWitness.timestampOrThrow());
        assertTrue(BATCH_VERIFIER
                .verifyBatch(timestampAlreadyCommitted, chain, finalWitness, 3)
                .isEmpty());
    }

    @Test
    void unsignedIdsCannotSubstituteForKeyContinuityOrWitnessVerification() {
        final var renamed = chain.stream()
                .map(c -> c.copyBuilder()
                        .sourceHintsConstructionId(9_999)
                        .targetHintsConstructionId(1)
                        .historyConstructionId(42)
                        .build())
                .toList();
        assertTrue(BATCH_VERIFIER
                .verifyBatch(
                        initialCheckpoint,
                        renamed,
                        finalWitness
                                .copyBuilder()
                                .targetHintsConstructionId(777)
                                .build(),
                        3)
                .isPresent());
        assertTrue(BATCH_VERIFIER
                .verifyBatch(initialCheckpoint, List.of(chain.get(2)), finalWitness, 3)
                .isEmpty());
        assertTrue(BATCH_VERIFIER
                .verifyBatch(initialCheckpoint, List.of(), firstWitness, 3)
                .isEmpty());
    }

    @Test
    void serializesNativeVectorsForEndpointAndEvmIntegration() throws Exception {
        for (final var certificate : chain) {
            assertEquals(
                    certificate, HintsKeyProvenance.PROTOBUF.parse(HintsKeyProvenance.PROTOBUF.toBytes(certificate)));
        }
        for (final var witness : List.of(firstWitness, finalWitness)) {
            assertEquals(witness, HintsKeyWitness.PROTOBUF.parse(HintsKeyWitness.PROTOBUF.toBytes(witness)));
        }
        final var output = Path.of("build", "generated", "provenance-witnesses", "native-v1.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), fixtureManifest());
        assertTrue(Files.size(output) > 0);
        // Compact witnesses contain one sibling and one aggregate, never a verification key or a WRAPS proof.
        assertTrue(HintsKeyWitness.PROTOBUF.toBytes(firstWitness).length()
                < firstWitness.signature().length() + 64);
        assertTrue(HintsKeyWitness.PROTOBUF.toBytes(finalWitness).length()
                < finalWitness.signature().length() + 80);
    }

    @Test
    void checkedInVectorsVerifyIndependentlyAndRejectTheirNegativeCases() throws Exception {
        final com.fasterxml.jackson.databind.JsonNode fixture;
        try (final var input =
                HintsKeyWitnessNativeTest.class.getResourceAsStream("/provenance-witnesses/native-v1.json")) {
            assertNotNull(input);
            fixture = new ObjectMapper().readTree(input);
        }
        var checkpoint = new Checkpoint(
                Bytes.fromHex(fixture.get("initialVerificationKeyHex").asText()),
                new Timestamp(
                        Long.parseLong(fixture.get("initialTimestampSeconds").asText()), 0));
        final var fixtureInitial = checkpoint;
        final var certificates = new ArrayList<HintsKeyProvenance>();
        for (final var certificate : fixture.get("certificates")) {
            certificates.add(HintsKeyProvenance.PROTOBUF.parse(
                    Bytes.fromHex(certificate.get("protobufHex").asText())));
        }
        final var witnesses = new ArrayList<HintsKeyWitness>();
        for (final var witness : fixture.get("witnesses")) {
            final var parsed = HintsKeyWitness.PROTOBUF.parse(
                    Bytes.fromHex(witness.get("protobufHex").asText()));
            assertEquals(
                    witness.get("blockRootHex").asText(),
                    HintsKeyWitnessVerifier.rootHash(parsed).toHex());
            assertEquals(
                    witness.get("protobufBytes").asLong(),
                    HintsKeyWitness.PROTOBUF.toBytes(parsed).length());
            witnesses.add(parsed);
        }
        for (final var batch : fixture.get("validBatches")) {
            final var links = new ArrayList<HintsKeyProvenance>();
            batch.get("certificateIndices").forEach(index -> links.add(certificates.get(index.asInt())));
            checkpoint = BATCH_VERIFIER
                    .verifyBatch(
                            checkpoint,
                            links,
                            witnesses.get(batch.get("witnessIndex").asInt()),
                            links.size())
                    .orElseThrow();
        }
        assertEquals(certificates.getLast().targetVerificationKey(), checkpoint.verificationKey());
        final var abandoned = HintsKeyProvenance.PROTOBUF.parse(Bytes.fromHex(
                fixture.get("abandonedCandidateCertificate").get("protobufHex").asText()));
        assertTrue(WITNESS_VERIFIER.verifyCertificate(abandoned, fixtureInitial.verificationKey()));
        assertTrue(BATCH_VERIFIER
                .verifyBatch(fixtureInitial, List.of(abandoned), null, 1)
                .isEmpty());
        assertTrue(BATCH_VERIFIER
                .verifyBatch(fixtureInitial, List.of(abandoned), witnesses.getFirst(), 1)
                .isEmpty());
        for (final var rejected : fixture.get("rejectedWitnesses")) {
            final var witness = HintsKeyWitness.PROTOBUF.parse(
                    Bytes.fromHex(rejected.get("witnessProtobufHex").asText()));
            assertFalse(
                    WITNESS_VERIFIER.verify(
                            witness,
                            Bytes.fromHex(
                                    rejected.get("expectedVerificationKeyHex").asText())),
                    rejected.get("reason").asText());
        }
    }

    private static Map<String, Object> fixtureManifest() {
        final var manifest = new LinkedHashMap<String, Object>();
        manifest.put("formatVersion", 1);
        manifest.put(
                "description",
                "Native hinTS rotations with deterministic public test keys; not production key material");
        manifest.put(
                "initialVerificationKeyHex", initialCheckpoint.verificationKey().toHex());
        manifest.put(
                "initialTimestampSeconds",
                Long.toString(initialCheckpoint.witnessedAt().seconds()));
        manifest.put(
                "certificates",
                chain.stream().map(HintsKeyWitnessNativeTest::certificateVector).toList());
        manifest.put("witnesses", List.of(witnessVector(firstWitness), witnessVector(finalWitness)));
        manifest.put("abandonedCandidateCertificate", certificateVector(abandonedCertificate));
        manifest.put(
                "rejectedWitnesses",
                List.of(
                        rejectedWitness(
                                "Valid witness under a different key",
                                firstWitness,
                                generations.get(4).verificationKey()),
                        rejectedWitness(
                                "Modified timestamp",
                                firstWitness
                                        .copyBuilder()
                                        .timestamp(new Timestamp(
                                                firstWitness.timestampOrThrow().seconds() + 1, 123))
                                        .build(),
                                generations.get(1).verificationKey()),
                        rejectedWitness(
                                "Modified sibling",
                                firstWitness
                                        .copyBuilder()
                                        .siblingHash(flip(firstWitness.siblingHash()))
                                        .build(),
                                generations.get(1).verificationKey()),
                        rejectedWitness(
                                "Modified signature",
                                firstWitness
                                        .copyBuilder()
                                        .signature(flip(firstWitness.signature()))
                                        .build(),
                                generations.get(1).verificationKey()),
                        rejectedWitness(
                                "Wrong digest",
                                firstWitness
                                        .copyBuilder()
                                        .hashAlgorithm(BlockHashAlgorithm.SHA2_384)
                                        .build(),
                                generations.get(1).verificationKey())));
        manifest.put(
                "validBatches",
                List.of(
                        Map.of("certificateIndices", List.of(0), "witnessIndex", 0),
                        Map.of("certificateIndices", List.of(1, 2), "witnessIndex", 1)));
        manifest.put(
                "rejectedCases",
                List.of(
                        "Abandoned candidate with no witness",
                        "Abandoned candidate with witness 0 relabeled to target construction 5",
                        "Witness 0 timestamp seconds + 1",
                        "Witness 0 sibling byte 0 XOR 1",
                        "Witness 0 signature byte 0 XOR 1",
                        "Witness 0 hashAlgorithm changed to SHA2_384",
                        "Repeated checkpoint timestamp"));
        return manifest;
    }

    private static Map<String, Object> rejectedWitness(
            final String reason, final HintsKeyWitness witness, final Bytes expectedKey) {
        return Map.of(
                "reason",
                reason,
                "expectedVerificationKeyHex",
                expectedKey.toHex(),
                "witnessProtobufHex",
                HintsKeyWitness.PROTOBUF.toBytes(witness).toHex());
    }

    private static Map<String, Object> certificateVector(final HintsKeyProvenance certificate) {
        return Map.of(
                "sourceVerificationKeyHex", certificate.sourceVerificationKey().toHex(),
                "targetVerificationKeyHex", certificate.targetVerificationKey().toHex(),
                "signatureHex", certificate.signature().toHex(),
                "protobufHex", HintsKeyProvenance.PROTOBUF.toBytes(certificate).toHex());
    }

    private static Map<String, Object> witnessVector(final HintsKeyWitness witness) {
        return Map.of(
                "targetConstructionId", Long.toString(witness.targetHintsConstructionId()),
                "timestampProtobufHex",
                        Timestamp.PROTOBUF.toBytes(witness.timestampOrThrow()).toHex(),
                "hashAlgorithm", witness.hashAlgorithm().name(),
                "siblingHashHex", witness.siblingHash().toHex(),
                "signatureHex", witness.signature().toHex(),
                "blockRootHex", HintsKeyWitnessVerifier.rootHash(witness).toHex(),
                "protobufHex", HintsKeyWitness.PROTOBUF.toBytes(witness).toHex(),
                "protobufBytes", HintsKeyWitness.PROTOBUF.toBytes(witness).length());
    }

    private static Generation generation(final int id) {
        final var privateKeys = new TreeMap<Integer, Bytes>();
        final var hints = new TreeMap<Integer, Bytes>();
        final var weights = new TreeMap<Integer, Long>();
        for (int party = 0; party < 3; party++) {
            final var privateKey = Bytes.wrap(HintsLibraryBridge.getInstance()
                    .generateSecretKey(seed(id * 10 + party).toByteArray()));
            privateKeys.put(party, privateKey);
            hints.put(party, LIBRARY.computeHints(crs, privateKey, party, NUM_PARTIES));
            weights.put(party, 1L);
        }
        final var keys = LIBRARY.preprocess(crs, hints, weights, NUM_PARTIES);
        assertNotNull(keys);
        return new Generation(id, Bytes.wrap(keys.verificationKey()), Bytes.wrap(keys.aggregationKey()), privateKeys);
    }

    private static HintsKeyProvenance certificate(final Generation source, final Generation target) {
        return HintsKeyProvenance.newBuilder()
                .sourceHintsConstructionId(source.id())
                .targetHintsConstructionId(target.id())
                .historyConstructionId(target.id() + 100)
                .sourceVerificationKey(source.verificationKey())
                .targetVerificationKey(target.verificationKey())
                .signature(sign(source, target.verificationKey(), List.of(0, 1)))
                .build();
    }

    private static HintsKeyWitness witness(
            final Generation generation, final long seconds, final BlockHashAlgorithm algorithm) {
        final var digest = algorithm == BlockHashAlgorithm.SHA2_256 ? DigestType.SHA_256 : DigestType.SHA_384;
        final var timestamp = new Timestamp(seconds, 123);
        final var branches = new Bytes[BlockRootTreeHasher.SLOT_COUNT];
        for (int i = 0; i < BlockRootTreeHasher.ASSIGNED_SLOT_COUNT; i++) {
            branches[i] = hashLeaf(digest.buildDigest(), Bytes.wrap("generation " + generation.id() + " branch " + i));
        }
        Arrays.fill(
                branches,
                BlockRootTreeHasher.ASSIGNED_SLOT_COUNT,
                branches.length,
                BlockRootTreeHasher.emptySubtreeFor(digest));
        final var unsigned = HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(generation.id())
                .timestamp(timestamp)
                .siblingHash(StreamingBlockRootTreeHasher.streamedRootOf(digest::buildDigest, branches))
                .hashAlgorithm(algorithm)
                .build();
        final var actualBlockRoot = BlockRootTree.computeBlockRootHash(
                digest, timestamp, Arrays.copyOf(branches, BlockRootTreeHasher.ASSIGNED_SLOT_COUNT));
        assertEquals(actualBlockRoot, HintsKeyWitnessVerifier.rootHash(unsigned));
        return unsigned.copyBuilder()
                .signature(sign(generation, actualBlockRoot, List.of(0, 1)))
                .build();
    }

    private static Bytes sign(final Generation generation, final Bytes message, final List<Integer> parties) {
        final var shares = new TreeMap<Integer, Bytes>();
        for (final var party : parties) {
            shares.put(party, LIBRARY.signBls(message, generation.privateKeys().get(party)));
        }
        final var aggregate =
                LIBRARY.aggregateSignatures(crs, generation.aggregationKey(), generation.verificationKey(), shares);
        assertNotNull(aggregate);
        return aggregate;
    }

    private static boolean freshMessageProof(
            final HintsKeyWitness messageProof,
            final Checkpoint checkpoint,
            final Instant now,
            final long maxAgeSeconds) {
        return WITNESS_VERIFIER.verify(messageProof, checkpoint.verificationKey())
                && !asInstant(messageProof.timestampOrThrow()).isBefore(now.minusSeconds(maxAgeSeconds))
                && !asInstant(messageProof.timestampOrThrow()).isAfter(now);
    }

    private static Bytes flip(final Bytes input) {
        final var bytes = input.toByteArray();
        bytes[0] ^= 1;
        return Bytes.wrap(bytes);
    }

    private static Bytes seed(final int value) {
        final var bytes = new byte[32];
        bytes[0] = (byte) value;
        return Bytes.wrap(bytes);
    }
}
