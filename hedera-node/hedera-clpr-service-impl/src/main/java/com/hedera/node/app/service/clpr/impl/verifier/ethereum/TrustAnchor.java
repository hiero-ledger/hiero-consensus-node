// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import com.hedera.hapi.node.state.clpr.EthereumPosCommitteeAnchor;
import com.hedera.hapi.node.state.clpr.EthereumPosTrustAnchor;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * The persisted trust anchor: the committees of two consecutive sync-committee periods, the fields of the signing
 * domain, and the pinned code hash of the source CLPR service contract. It is stored as a protobuf
 * {@code EthereumPosTrustAnchor}, so fields can be added later without breaking the anchors already in state.
 *
 * <p>The anchor trusts {@code current} for {@code period} and {@code next} for {@code period + 1}, so a header signed
 * in either period verifies without a rotation. A rotation proves the committee of {@code period + 2} and slides the
 * window by one period. The {@code trustAnchorId} is the period as 8 big-endian bytes, which is how the relay reads
 * it back.
 *
 * @param preset                  the consensus preset of the channel
 * @param genesisValidatorsRoot32 the beacon chain genesis validators root
 * @param forkVersion4            the fork version of the signing domain
 * @param period                  the sync-committee period {@code current} signs for
 * @param current                 the committee of {@code period}
 * @param next                    the committee of {@code period + 1}
 * @param codeHash32              the keccak256 of the source CLPR service contract's code, which every proven account
 *                                must carry
 */
record TrustAnchor(
        @NonNull SyncCommitteePreset preset,
        @NonNull byte[] genesisValidatorsRoot32,
        @NonNull byte[] forkVersion4,
        long period,
        @NonNull SyncCommittee current,
        @NonNull SyncCommittee next,
        @NonNull byte[] codeHash32) {

    private static final int HASH_LENGTH = 32;

    TrustAnchor {
        Objects.requireNonNull(preset, "preset must not be null");
        genesisValidatorsRoot32 = checkedCopy(genesisValidatorsRoot32, HASH_LENGTH, "genesisValidatorsRoot");
        forkVersion4 = checkedCopy(forkVersion4, Ssz.FORK_VERSION_LENGTH, "forkVersion");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(next, "next must not be null");
        codeHash32 = checkedCopy(codeHash32, HASH_LENGTH, "codeHash");
    }

    /**
     * Decodes a persisted trust anchor.
     *
     * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if the bytes are not a valid
     *     {@code EthereumPosTrustAnchor}: unknown fields, a missing committee, a wrong field length, or an unknown
     *     preset code
     */
    @NonNull
    static TrustAnchor decode(@NonNull final byte[] trustAnchor) {
        final EthereumPosTrustAnchor anchor;
        try {
            anchor = EthereumPosTrustAnchor.PROTOBUF.parseStrict(
                    Bytes.wrap(trustAnchor).toReadableSequentialData());
        } catch (final ParseException | RuntimeException e) {
            throw EthProofs.fail("trustAnchor is not a valid EthereumPosTrustAnchor", e);
        }
        if (!anchor.hasCurrent() || !anchor.hasNext()) {
            throw EthProofs.fail("trustAnchor must hold both the current and the next committee");
        }
        final SyncCommitteePreset preset = SyncCommitteePreset.fromCode(anchor.preset());
        try {
            return new TrustAnchor(
                    preset,
                    anchor.genesisValidatorsRoot().toByteArray(),
                    anchor.forkVersion().toByteArray(),
                    anchor.period(),
                    toSyncCommittee(anchor.currentOrThrow()),
                    toSyncCommittee(anchor.nextOrThrow()),
                    anchor.codeHash().toByteArray());
        } catch (final IllegalArgumentException e) {
            throw EthProofs.fail("trustAnchor has a field of the wrong length", e);
        }
    }

    @NonNull
    private static SyncCommittee toSyncCommittee(@NonNull final EthereumPosCommitteeAnchor committee) {
        return new SyncCommittee(
                committee.pubkeysRoot().toByteArray(),
                committee.aggregatePubkey().toByteArray());
    }

    /**
     * Encodes this anchor as the {@code EthereumPosTrustAnchor} protobuf {@link #decode} reads.
     */
    @NonNull
    byte[] encode() {
        return EthereumPosTrustAnchor.PROTOBUF
                .toBytes(EthereumPosTrustAnchor.newBuilder()
                        .preset(preset.code())
                        .genesisValidatorsRoot(Bytes.wrap(genesisValidatorsRoot32))
                        .forkVersion(Bytes.wrap(forkVersion4))
                        .period(period)
                        .current(toProto(current))
                        .next(toProto(next))
                        .codeHash(Bytes.wrap(codeHash32))
                        .build())
                .toByteArray();
    }

    @NonNull
    private static EthereumPosCommitteeAnchor toProto(@NonNull final SyncCommittee committee) {
        return EthereumPosCommitteeAnchor.newBuilder()
                .pubkeysRoot(Bytes.wrap(committee.pubkeysRoot32()))
                .aggregatePubkey(Bytes.wrap(committee.aggregatePubkey48()))
                .build();
    }

    /**
     * The {@code trustAnchorId}: the period as 8 big-endian bytes.
     */
    @NonNull
    byte[] id() {
        return ByteBuffer.allocate(Long.BYTES).putLong(period).array();
    }

    /**
     * The committee that signs in {@code signingPeriod}: {@code current} for {@link #period}, {@code next} for the
     * period after it.
     *
     * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if the anchor trusts no committee for
     *     {@code signingPeriod}
     */
    @NonNull
    SyncCommittee committeeFor(final long signingPeriod) {
        if (signingPeriod == period) {
            return current;
        }
        if (signingPeriod == period + 1) {
            return next;
        }
        throw EthProofs.fail("the trust anchor trusts committees for periods " + period + " and " + (period + 1)
                + ", not for period " + signingPeriod);
    }

    /**
     * The anchor one period later: it trusts {@code next} for {@code period + 1} and {@code nextNext} for
     * {@code period + 2}. The domain fields and the code hash carry over.
     */
    @NonNull
    TrustAnchor rotatedTo(@NonNull final SyncCommittee nextNext) {
        return new TrustAnchor(preset, genesisValidatorsRoot32, forkVersion4, period + 1, next, nextNext, codeHash32);
    }

    @Override
    public byte[] genesisValidatorsRoot32() {
        return genesisValidatorsRoot32.clone();
    }

    @Override
    public byte[] forkVersion4() {
        return forkVersion4.clone();
    }

    @Override
    public byte[] codeHash32() {
        return codeHash32.clone();
    }
}
