// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;

/**
 * Shared ABI definitions for the CLPR verifier system contracts, used by every EVM verifier (Hiero TSS,
 * Besu QBFT, Ethereum, Sei) on both sides of the wire: the producers ({@code *VerifyConfig/BundleCall} and
 * their translators, in {@code hedera-smart-contract-service-impl}) and the consumer
 * ({@link EvmClprVerifier}, here). Defining them once keeps the encode and decode sides from drifting.
 *
 * <p>These live in this module (not {@code systemcontracts/common}) because {@link EvmClprVerifier} — the
 * decoder — cannot depend on {@code hedera-smart-contract-service-impl}; that dependency runs the other way.
 */
public final class ClprVerifierAbi {
    private ClprVerifierAbi() {}

    /**
     * Manifest-aware {@code verifyConfig(bytes,bytes32,bytes)} return: the config fields (7-field throttles)
     * followed by the {@link ClprEndpointManifest} struct. Registered as the return type of the
     * manifest-aware config method by each config translator and decoded by {@link EvmClprVerifier}.
     */
    public static final String VERIFY_CONFIG_OUTPUTS =
            "(bytes,string,bytes,uint96,(uint32,uint64,uint64,uint32,uint64,uint32,uint32),bytes,bytes,(uint64,bytes,(string,uint32,bytes,bytes)[]))";

    /** {@link #VERIFY_CONFIG_OUTPUTS} parsed, for the decode side. */
    public static final TupleType<Tuple> VERIFY_CONFIG_RETURN = TupleType.parse(VERIFY_CONFIG_OUTPUTS);

    /**
     * Manifest-aware {@code verifyBundle} return: queue metadata (see {@link #metadataTuple}), message payloads,
     * new trust anchor and anchor id, followed by a {@link ClprEndpointManifest}. Each {@code *BundleCall}
     * encodes this {@link TupleType} directly and {@link EvmClprVerifier} decodes it. A {@code version == 0}
     * manifest member means "absent".
     */
    public static final TupleType<Tuple> VERIFY_BUNDLE_RETURN = TupleType.parse(
            "((uint64,bytes32,uint64,bytes32,uint8,uint64),bytes[],bytes,bytes,(uint64,bytes,(string,uint32,bytes,bytes)[]))");

    /**
     * The metadata-absent sentinel {@code metaTuple}: every field zero, with the two {@code bytes32}
     * members as full 32-byte zero arrays (not empty) so it encodes against the fixed ABI type. A bundle
     * whose queue metadata is absent — a trust-anchor rotation or a manifest-only recovery (spec §8.1.4) —
     * carries this in place of real metadata. Both the {@code *VerifyBundleCall} producers and
     * {@link EvmClprVerifier} reference this one definition so the encode and decode sides cannot drift.
     */
    @NonNull
    public static Tuple absentMetadataTuple() {
        return Tuple.of(BigInteger.ZERO, new byte[32], BigInteger.ZERO, new byte[32], 0, BigInteger.ZERO);
    }

    /**
     * Encodes {@link ClprQueueMetadata} as the six-field {@code verifyBundle} metaTuple
     * {@code (uint64 nextMessageId, bytes32 sentRunningHash, uint64 receivedMessageId, bytes32 receivedRunningHash,
     * uint8 status, uint64 endpointManifestVersion)}. Returns {@code null} when the metadata cannot be ABI-encoded:
     * PBJ reads a proto {@code uint64 >= 2^63} as a negative {@code long}, and a running hash must be exactly 32
     * bytes. The encoder would throw on either, so producers fail the call instead of letting it escape.
     *
     * <p>{@code endpointManifestVersion} is the sender's cached version of the receiver's endpoint manifest. Every
     * producer sources it from PROVEN state — the Hiero TSS producer from a proven {@code ClprChannel} leaf, and the
     * Besu QBFT, Sei and Ethereum producers from the proven Channel storage slot (offset 16) via the
     * {@link #metadataTuple(ClprQueueMetadata, long)} overload — never from relayed content, which a relay could
     * inflate. It drives the receiver's node-local decision whether to re-send its own manifest (liveness), never
     * consensus state.
     */
    @Nullable
    public static Tuple metadataTuple(@NonNull final ClprQueueMetadata meta) {
        return metadataTuple(meta, meta.endpointManifestVersion());
    }

    /**
     * As {@link #metadataTuple(ClprQueueMetadata)}, but with {@code endpointManifestVersion} supplied explicitly —
     * used by the EVM producers (Besu QBFT, Sei, Ethereum) to carry the version PROVEN from the peer Channel storage
     * slot (offset 16) rather than the unproven copy in {@code meta}.
     */
    @Nullable
    public static Tuple metadataTuple(@NonNull final ClprQueueMetadata meta, final long endpointManifestVersion) {
        if (meta.nextMessageId() < 0
                || meta.receivedMessageId() < 0
                || endpointManifestVersion < 0
                || meta.sentRunningHash().length() != 32
                || meta.receivedRunningHash().length() != 32) {
            return null;
        }
        return Tuple.of(
                BigInteger.valueOf(meta.nextMessageId()),
                meta.sentRunningHash().toByteArray(),
                BigInteger.valueOf(meta.receivedMessageId()),
                meta.receivedRunningHash().toByteArray(),
                meta.status().protoOrdinal(),
                BigInteger.valueOf(endpointManifestVersion));
    }

    /**
     * True when {@code metaTuple} is the {@link #absentMetadataTuple() metadata-absent sentinel}, i.e. its
     * {@code nextMessageId} (member 0) is zero. This is unambiguous: a real bundle's {@code nextMessageId}
     * is always {@code >= 1} ({@code ackedMessageId + 1 + messages.size()}), so zero can only be the
     * sentinel. {@code nextMessageId} alone is the discriminator — the other members can legitimately be
     * zero on a genuine bundle (e.g. at genesis).
     */
    public static boolean isMetadataAbsent(@NonNull final Tuple metaTuple) {
        return ((BigInteger) metaTuple.get(0)).signum() == 0;
    }

    /**
     * Encodes a {@link ClprEndpointManifest} as the ABI struct
     * {@code (uint64 version, bytes serviceAddress, (string,uint32,bytes,bytes)[] endpoints)} — the manifest
     * member of the manifest-aware config and bundle returns.
     */
    @NonNull
    public static Tuple manifestStructTuple(@NonNull final ClprEndpointManifest manifest) {
        final Tuple[] endpointTuples = manifest.endpoints().stream()
                .map(ep -> {
                    final ClprServiceEndpoint se = ep.serviceEndpointOrElse(ClprServiceEndpoint.DEFAULT);
                    return Tuple.of(
                            se.ipAddress(),
                            (long) se.port(),
                            ep.tlsCertificate().toByteArray(),
                            ep.accountId().toByteArray());
                })
                .toArray(Tuple[]::new);
        return Tuple.of(
                BigInteger.valueOf(manifest.version()),
                manifest.serviceAddress().toByteArray(),
                endpointTuples);
    }
}
