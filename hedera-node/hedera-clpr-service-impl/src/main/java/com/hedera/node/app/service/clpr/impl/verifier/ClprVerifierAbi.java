// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
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
     * {@code verifyConfig(bytes,bytes32,bytes)} V3 (context + manifest) return: the config fields (7-field
     * throttles) followed by the {@link ClprEndpointManifest} struct. Registered as the return type of the
     * V3 config method by each config translator and decoded by {@link EvmClprVerifier}.
     */
    public static final String VERIFY_CONFIG_V3_OUTPUTS =
            "(bytes,string,bytes,uint96,(uint32,uint64,uint64,uint32,uint64,uint32,uint32),bytes,bytes,(uint64,bytes,(string,uint32,bytes,bytes)[]))";

    /** {@link #VERIFY_CONFIG_V3_OUTPUTS} parsed, for the decode side. */
    public static final TupleType<Tuple> VERIFY_CONFIG_V3_RETURN = TupleType.parse(VERIFY_CONFIG_V3_OUTPUTS);

    /**
     * {@code verifyBundle} V3 return: the V2 members (queue metadata, message payloads, new trust anchor +
     * id) plus a trailing {@link ClprEndpointManifest}. V3 reuses the V2 selector — only the return grows —
     * so, unlike the config V3, it is not a registered {@code SystemContractMethod}; each {@code *BundleCall}
     * encodes this {@link TupleType} directly and {@link EvmClprVerifier} decodes it. A {@code version == 0}
     * manifest member means "absent".
     */
    public static final TupleType<Tuple> VERIFY_BUNDLE_V3_RETURN = TupleType.parse(
            "((uint64,bytes32,uint64,bytes32,uint8),bytes[],bytes,bytes,(uint64,bytes,(string,uint32,bytes,bytes)[]))");

    /**
     * The metadata-absent sentinel {@code metaTuple}: every field zero, with the two {@code bytes32}
     * members as full 32-byte zero arrays (not empty) so it encodes against the fixed ABI type. A bundle
     * whose queue metadata is absent — a trust-anchor rotation or a manifest-only recovery (spec §8.1.4) —
     * carries this in place of real metadata. Both the {@code *VerifyBundleCall} producers and
     * {@link EvmClprVerifier} reference this one definition so the encode and decode sides cannot drift.
     */
    @NonNull
    public static Tuple absentMetadataTuple() {
        return Tuple.of(BigInteger.ZERO, new byte[32], BigInteger.ZERO, new byte[32], 0);
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
     * member of the V3 config and bundle returns.
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
