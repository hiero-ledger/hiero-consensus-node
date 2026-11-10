// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.hapi.node.state.clpr.ClprBundleFormat.CLPR_BUNDLE_FORMAT_HIERO_PROOF_V1;
import static com.hedera.hapi.node.state.clpr.ClprBundleFormat.CLPR_BUNDLE_FORMAT_STATE_PROOF;
import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE;
import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.block.stream.HieroBlockWitness;
import com.hedera.hapi.block.stream.HieroProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.history.impl.HintsKeyProvenanceVerifier;
import com.hedera.node.app.history.impl.HintsKeyProvenanceVerifier.Checkpoint;
import com.hedera.node.app.history.impl.HintsKeyWitnessVerifier;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager.BundleProof;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Emits bundles from the sync producer for the Solidity verifier's real `submitBundle` tests.
 * The environment variable must point to a disposable checkout of the contracts repository.
 */
@EnabledIfEnvironmentVariable(named = "CLPR_HIERO_INTEROP_DIR", matches = ".+")
class ClprHieroProofInteropTest {
    private static final Bytes FIXTURE_CHANNEL_ID =
            Bytes.fromHex("e1022276af73c7a88c9fa00f7e844bbc4a8fda0cc6051d9c99926b74cf9c3d84");
    private static final ClprThrottles THROTTLES = ClprThrottles.newBuilder()
            .maxMessagesPerBundle(5)
            .maxSyncBytes(64_000)
            .build();
    private static final HintsLibraryImpl HINTS = new HintsLibraryImpl();
    private static final HintsKeyWitnessVerifier WITNESS_VERIFIER = new HintsKeyWitnessVerifier(HINTS);
    private static final ClprHieroProofSelfCheck SELF_CHECK =
            new ClprHieroProofSelfCheck(HINTS, WITNESS_VERIFIER, mock(Metrics.class));

    @Test
    void exportBundlesForSolidity() throws Exception {
        final var checkout = Path.of(System.getenv("CLPR_HIERO_INTEROP_DIR"));
        final var fixtures = checkout.resolve("test/verifiers/hiero/fixtures");
        final var rotation = fixtures.resolve("rotation");
        final var witness = fixtures.resolve("witness");
        final var firstLink =
                link(1, rotation.resolve("link01.bin"), rotation.resolve("key0.bin"), rotation.resolve("key1.bin"));
        final var secondLink =
                link(2, rotation.resolve("link12.bin"), rotation.resolve("key1.bin"), rotation.resolve("key2.bin"));
        final var thirdLink =
                link(3, witness.resolve("link23.bin"), rotation.resolve("key2.bin"), witness.resolve("key3.bin"));
        final var fourthLink =
                link(4, witness.resolve("link34.bin"), witness.resolve("key3.bin"), witness.resolve("key4.bin"));
        final var output = Files.createDirectories(fixtures.resolve("java-interop/v1"));

        final var atKey0 = stateProof(fixtures.resolve("stateProof.bin"));
        final var atKey1 = stateProof(rotation.resolve("bundle-k1.bin"));
        final var atKey2 = stateProof(rotation.resolve("bundle-k2-nomsg.bin"));
        final var atKey4 = stateProof(witness.resolve("bundle-k4.bin"));
        final var first = record(firstLink, null);
        final var second = record(secondLink, witness(witness, "w2", secondLink.targetHintsConstructionId()));
        final var third = record(thirdLink, witness(witness, "w3", thirdLink.targetHintsConstructionId()));
        final var fourth = record(fourthLink, null);
        final var fullHistory = List.of(first, second, third, fourth);
        write(output, "matching-anchor.bin", stateBundle(firstLink.sourceVerificationKey(), atKey0, List.of()));
        write(output, "short-rotation.bin", stateBundle(firstLink.sourceVerificationKey(), atKey1, List.of(first)));
        write(
                output,
                "catch-up-1.bin",
                produceBundle(firstLink.sourceVerificationKey(), signature(atKey2), List.of(first, second), null));
        write(
                output,
                "catch-up-2.bin",
                produceBundle(thirdLink.sourceVerificationKey(), signature(atKey4), fullHistory, null));
        write(output, "after-catch-up.bin", stateBundle(fourthLink.sourceVerificationKey(), atKey4, fullHistory));
        exportNativeRotations(output, atKey4);
        verifyNativeFormatIsUnchanged(atKey0);

        // This SHA-256 witness and predecessor certificate come directly from the consensus-node native vectors.
        try (final var input = requireNonNull(getClass().getResourceAsStream("/provenance-witnesses/native-v1.json"))) {
            final var vectors = new ObjectMapper().readTree(input);
            final var certificate = HintsKeyProvenance.PROTOBUF.parse(Bytes.fromHex(
                    vectors.get("certificates").get(0).get("protobufHex").asText()));
            final var nativeWitness = HintsKeyWitness.PROTOBUF.parse(Bytes.fromHex(
                    vectors.get("witnesses").get(0).get("protobufHex").asText()));
            write(
                    output,
                    "native-witness.bin",
                    produceBundle(
                            certificate.sourceVerificationKey(),
                            certificate.targetVerificationKey().append(nativeWitness.signature()),
                            List.of(record(certificate, nativeWitness)),
                            null));
            write(output, "native-initial-key.bin", certificate.sourceVerificationKey());
            write(output, "native-final-key.bin", certificate.targetVerificationKey());
        }

        try (final var source =
                requireNonNull(getClass().getResourceAsStream("/clpr-interop/HieroJavaProofInterop.t.sol"))) {
            Files.copy(
                    source,
                    checkout.resolve("test/logic/bundle-logic/HieroJavaProofInterop.t.sol"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void exportNativeRotations(final Path output, final StateProof template) throws Exception {
        final var nativeRotation = new NativeHieroProofFixture(HINTS).rotate(template);
        final var proof = withWrapsSizedTail(nativeRotation.finalProof());
        final var signature = signature(proof);
        final var history = nativeRotation.history();
        final var initial = nativeRotation.initialKey();
        final var afterFirst = history.getFirst().provenanceOrThrow().targetVerificationKey();
        final var afterSecond = history.get(8).provenanceOrThrow().targetVerificationKey();
        final var finalKey = history.getLast().provenanceOrThrow().targetVerificationKey();

        final var first = produceBundle(initial, signature, history, null);
        final var second = produceBundle(afterFirst, signature, history, null);
        final var state = stateBundle(afterSecond, proof, history);

        assertThat(HieroProof.PROTOBUF.parse(first).keyProvenanceOrThrow().links())
                .hasSize(1);
        assertThat(HieroProof.PROTOBUF.parse(second).keyProvenanceOrThrow().links())
                .hasSize(8);
        assertThat(HieroProof.PROTOBUF.parse(state).keyProvenanceOrThrow().links())
                .hasSize(2);
        assertThat(produceBundle(initial, signature, history, null)).isEqualTo(first);

        final var reference = new HintsKeyProvenanceVerifier(WITNESS_VERIFIER);
        final var initialCheckpoint = Checkpoint.fromTrustedKey(initial);
        final var firstCheckpoint = reference
                .verifyBatch(
                        initialCheckpoint,
                        List.of(history.getFirst().provenanceOrThrow()),
                        history.getFirst().witnessOrThrow(),
                        8)
                .orElseThrow();
        final var secondCheckpoint = reference
                .verifyBatch(
                        firstCheckpoint,
                        history.subList(1, 9).stream()
                                .map(HintsKeyWitnessRecord::provenanceOrThrow)
                                .toList(),
                        history.get(8).witnessOrThrow(),
                        8)
                .orElseThrow();
        assertThat(firstCheckpoint.verificationKey()).isEqualTo(afterFirst);
        assertThat(secondCheckpoint.verificationKey()).isEqualTo(afterSecond);
        assertThat(reference.verifyBatch(
                        firstCheckpoint,
                        List.of(history.getFirst().provenanceOrThrow()),
                        history.getFirst().witnessOrThrow(),
                        8))
                .isEmpty();

        final var missingWitness = new ArrayList<>(history);
        missingWitness.set(0, record(history.getFirst().provenanceOrThrow(), null));
        final var waiting = producer(initial, signature, missingWitness, null);
        assertThat(waiting.next(false)).isNull();
        assertThat(waiting.nextStartingMessageId()).isEqualTo(1);
        final var unknown = producer(Bytes.wrap(new byte[1_096]), signature, history, null);
        assertThat(unknown.next(false)).isNull();
        assertThat(unknown.nextStartingMessageId()).isEqualTo(1);

        write(output, "rotations-initial-key.bin", initial);
        write(output, "rotations-first-key.bin", afterFirst);
        write(output, "rotations-second-key.bin", afterSecond);
        write(output, "rotations-final-key.bin", finalKey);
        write(output, "rotations-first.bin", first);
        write(output, "rotations-second.bin", second);
        write(output, "rotations-state.bin", state);
        write(
                output,
                "rotations-missing-witness.bin",
                HieroProof.PROTOBUF.toBytes(HieroProof.PROTOBUF
                        .parse(first)
                        .copyBuilder()
                        .blockWitness((HieroBlockWitness) null)
                        .build()));
        verifyFixtureChecksums(output);
    }

    private static void verifyFixtureChecksums(final Path output) throws IOException {
        final String checksums;
        try (final var input = requireNonNull(
                ClprHieroProofInteropTest.class.getResourceAsStream("/clpr-interop/native-hiero-proof-v1.sha256"))) {
            checksums = new String(input.readAllBytes(), UTF_8);
        }
        for (final var line : checksums.lines().toList()) {
            final var entry = line.split("  ", 2);
            final var bytes = Files.readAllBytes(output.resolve(entry[1]));
            assertThat(Bytes.wrap(DigestType.SHA_256.buildDigest().digest(bytes))
                            .toHex())
                    .as(entry[1])
                    .isEqualTo(entry[0]);
        }
    }

    private static void verifyNativeFormatIsUnchanged(final StateProof nativeProof) {
        final var manager = mock(ClprStateProofManager.class);
        final var payload = StateProof.PROTOBUF.toBytes(nativeProof);
        given(manager.buildBundleProof(FIXTURE_CHANNEL_ID, 1L, THROTTLES, true, false))
                .willReturn(new BundleProof(payload, 1, 1));
        final var request = ClprBundleRequest.newBuilder()
                .bundleFormat(CLPR_BUNDLE_FORMAT_STATE_PROOF)
                .build();
        final var channel = channelWithMessageProof(true);
        assertThat(new ClprBundleProducer(manager, channel, null, true).next(false))
                .isEqualTo(payload);
        assertThat(new ClprBundleProducer(manager, channel, request, true).next(false))
                .isEqualTo(payload);
    }

    private static StateProof stateProof(final Path proofFile) throws IOException, ParseException {
        return withWrapsSizedTail(StateProof.PROTOBUF.parse(read(proofFile)));
    }

    private static StateProof withWrapsSizedTail(final StateProof proof) {
        // The contract fixtures already have compact signatures. Append a WRAPS-sized tail to exercise
        // compaction while leaving the real signing key, aggregate and Merkle paths intact.
        return proof.copyBuilder()
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(signature(proof).append(Bytes.wrap(new byte[11_368])))
                        .build())
                .build();
    }

    private static Bytes signature(final StateProof proof) {
        return proof.signedBlockProofOrThrow().blockSignature();
    }

    private static Bytes stateBundle(
            final Bytes anchorKey, final StateProof proof, final List<HintsKeyWitnessRecord> records) {
        return produceBundle(anchorKey, signature(proof), records, proof);
    }

    private static Bytes produceBundle(
            final Bytes anchorKey,
            final Bytes snapshotSignature,
            final List<HintsKeyWitnessRecord> records,
            @Nullable final StateProof messageProof) {
        final var producer = producer(anchorKey, snapshotSignature, records, messageProof);
        final var bundle = producer.next(false);
        assertThat(bundle).isNotNull();
        assertThat(producer.nextStartingMessageId()).isEqualTo(messageProof == null ? 1 : 2);
        if (messageProof == null) {
            assertThat(producer.next(false)).isNull();
        }
        return requireNonNull(bundle);
    }

    private static ClprBundleProducer producer(
            final Bytes anchorKey,
            final Bytes snapshotSignature,
            final List<HintsKeyWitnessRecord> records,
            @Nullable final StateProof messageProof) {
        final var manager = mock(ClprStateProofManager.class);
        final var snapshot = mock(BlockProvenSnapshot.class);
        given(manager.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(manager.supportsHieroProofV1()).willReturn(true);
        given(manager.verifyHieroProof(any(), any()))
                .willAnswer(invocation -> SELF_CHECK.verify(invocation.getArgument(0), invocation.getArgument(1)));
        given(snapshot.tssSignature()).willReturn(snapshotSignature);
        given(snapshot.keyProvenanceWithWitnesses()).willReturn(records);
        given(snapshot.path())
                .willReturn(MerklePath.newBuilder()
                        .siblings(SiblingNode.newBuilder()
                                .hash(Bytes.wrap(new byte[32]))
                                .build())
                        .build());
        // A signed snapshot and its Merkle proof normally come from the running node. Here the proof builder
        // supplies the cryptographic fixture, or reports an empty queue, while the real producer chooses the wire
        // proof.
        if (messageProof != null) {
            given(manager.buildBundleProof(
                            eq(snapshot), eq(FIXTURE_CHANNEL_ID), eq(1L), eq(THROTTLES), eq(true), eq(false), any()))
                    .willAnswer(invocation -> {
                        final ToLongFunction<StateProof> payloadSize = invocation.getArgument(6);
                        assertThat(payloadSize.applyAsLong(messageProof)).isLessThan(messageProof.protobufSize());
                        return new BundleProof(StateProof.PROTOBUF.toBytes(messageProof), 1, 1);
                    });
        }
        final var request = ClprBundleRequest.newBuilder()
                .currentStatus(ACTIVE)
                .currentTrustAnchorId(keccak256DigestOf(anchorKey))
                .bundleFormat(CLPR_BUNDLE_FORMAT_HIERO_PROOF_V1)
                .build();
        return new ClprBundleProducer(manager, channelWithMessageProof(messageProof != null), request, true, 2);
    }

    private static ClprChannel channelWithMessageProof(final boolean hasMessageProof) {
        return ClprChannel.newBuilder()
                .channelId(FIXTURE_CHANNEL_ID)
                .status(ACTIVE)
                .nextMessageId(hasMessageProof ? 2 : 1)
                .peerThrottles(THROTTLES)
                .build();
    }

    private static HintsKeyWitnessRecord record(
            final HintsKeyProvenance link, @Nullable final HintsKeyWitness witness) {
        return HintsKeyWitnessRecord.newBuilder()
                .provenance(link)
                .witness(witness)
                .build();
    }

    private static HintsKeyProvenance link(
            final long constructionId, final Path signature, final Path source, final Path target) throws IOException {
        return HintsKeyProvenance.newBuilder()
                .targetHintsConstructionId(constructionId)
                .sourceVerificationKey(read(source))
                .targetVerificationKey(read(target))
                .signature(read(signature))
                .build();
    }

    private static HintsKeyWitness witness(final Path directory, final String name, final long constructionId)
            throws IOException, ParseException {
        return HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(constructionId)
                .hashAlgorithm(SHA2_256)
                .timestamp(Timestamp.PROTOBUF.parse(read(directory.resolve(name + "-timestamp.bin"))))
                .siblingHash(read(directory.resolve(name + "-sibling.bin")))
                .signature(read(directory.resolve(name + "-signature.bin")))
                .build();
    }

    private static Bytes read(final Path file) throws IOException {
        return Bytes.wrap(Files.readAllBytes(file));
    }

    private static void write(final Path output, final String name, final Bytes bytes) throws IOException {
        Files.write(output.resolve(name), bytes.toByteArray());
    }
}
