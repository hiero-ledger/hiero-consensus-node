// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static com.hedera.node.app.history.impl.HintsKeyWitnessVerifier.rootHash;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.hiero.base.crypto.DigestType;

/** Real native hinTS signing across eleven rotations, with fixed public test keys. */
final class NativeHieroProofFixture {
    private static final int PARTY_COUNT = 4;
    private static final int ROTATIONS = 11;
    private static final long FIRST_TIMESTAMP = 1_735_689_600L;

    record Rotation(Bytes initialKey, List<HintsKeyWitnessRecord> history, StateProof finalProof) {}

    private record Generation(int id, Bytes verificationKey, Bytes aggregationKey, List<Bytes> privateKeys) {}

    private final HintsLibraryImpl library;
    private final Bytes crs;

    NativeHieroProofFixture(final HintsLibraryImpl library) {
        this.library = library;
        final var initialCrs = library.newCrs((short) PARTY_COUNT);
        crs = decodeCrsUpdate(initialCrs.length(), library.updateCrs(initialCrs, seed(241)))
                .crs();
    }

    Rotation rotate(final StateProof template) {
        final var initial = generation(0);
        var predecessor = initial;
        final var records = new ArrayList<HintsKeyWitnessRecord>();
        for (int id = 1; id <= ROTATIONS; id++) {
            final var next = generation(id);
            final var link = HintsKeyProvenance.newBuilder()
                    .sourceHintsConstructionId(predecessor.id() + 1L)
                    .targetHintsConstructionId(id + 1L)
                    .sourceVerificationKey(predecessor.verificationKey())
                    .targetVerificationKey(next.verificationKey())
                    .signature(sign(predecessor, next.verificationKey()))
                    .build();
            final var record = HintsKeyWitnessRecord.newBuilder().provenance(link);
            if (id == 1 || id == 9) {
                record.witness(witness(next));
            }
            records.add(record.build());
            predecessor = next;
        }
        final var leafPath = template.paths().stream()
                .filter(MerklePath::hasStateItemLeaf)
                .findFirst()
                .orElseThrow();
        final var blockRoot =
                Bytes.wrap(StateProofVerifier.computeBlockRootHashFromPath(leafPath, DigestType.SHA_256.buildDigest()));
        final var finalProof = template.copyBuilder()
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(predecessor.verificationKey().append(sign(predecessor, blockRoot)))
                        .build())
                .build();
        return new Rotation(initial.verificationKey(), List.copyOf(records), finalProof);
    }

    private Generation generation(final int id) {
        final var privateKeys = new ArrayList<Bytes>();
        final var hints = new TreeMap<Integer, Bytes>();
        final var weights = new TreeMap<Integer, Long>();
        for (int party = 0; party < 3; party++) {
            final var privateKey = Bytes.wrap(HintsLibraryBridge.getInstance()
                    .generateSecretKey(seed(id * 10 + party).toByteArray()));
            privateKeys.add(privateKey);
            hints.put(party, library.computeHints(crs, privateKey, party, PARTY_COUNT));
            weights.put(party, 1L);
        }
        final var keys = requireNonNull(library.preprocess(crs, hints, weights, PARTY_COUNT));
        return new Generation(id, Bytes.wrap(keys.verificationKey()), Bytes.wrap(keys.aggregationKey()), privateKeys);
    }

    private HintsKeyWitness witness(final Generation generation) {
        final var unsigned = HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(generation.id() + 1L)
                .timestamp(new Timestamp(FIRST_TIMESTAMP + generation.id(), 123))
                .siblingHash(seed(generation.id()))
                .hashAlgorithm(SHA2_256)
                .build();
        return unsigned.copyBuilder()
                .signature(sign(generation, rootHash(unsigned)))
                .build();
    }

    private Bytes sign(final Generation generation, final Bytes message) {
        final var shares = new TreeMap<Integer, Bytes>();
        for (int party = 0; party < 2; party++) {
            shares.put(party, library.signBls(message, generation.privateKeys().get(party)));
        }
        return requireNonNull(
                library.aggregateSignatures(crs, generation.aggregationKey(), generation.verificationKey(), shares));
    }

    private static Bytes seed(final int value) {
        final var bytes = new byte[32];
        bytes[0] = (byte) value;
        return Bytes.wrap(bytes);
    }
}
