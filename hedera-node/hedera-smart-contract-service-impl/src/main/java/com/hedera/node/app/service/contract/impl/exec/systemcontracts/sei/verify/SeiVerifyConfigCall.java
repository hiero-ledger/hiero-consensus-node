// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.sei.SeiCometBftProofVerifier;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyConfig(bytes configPayload) returns (bytes)} for the Sei verifier
 * system contract (EVM address {@code 0x170}).
 *
 * <p>The {@code configPayload} is a proto-encoded {@code ClprSeiLedgerConfigurationPayload}
 * carrying the validator set and ledger configuration needed to seed the channel's initial
 * Sei trust anchor.
 *
 * <p>Payload validation is delegated to
 * {@link SeiCometBftProofVerifier#verifyConfigPayload(byte[])}. On success the decoded
 * {@link ClprLedgerConfiguration} bytes (with the derived {@code initial_trust_anchor}) are
 * returned so {@code ClprCompleteChannelHandler} can seed Channel state from them directly.
 */
public class SeiVerifyConfigCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(SeiVerifyConfigCall.class);
    private static final long GAS_REQUIREMENT = 50_000L;

    private final byte[] configPayload;

    /** Non-null on the V2 and V3 (context-bound) paths. */
    @Nullable
    private final byte[] channelId32;

    /** Non-null only on the V3 (manifest-aware) path; selects v3Success over v2Success. */
    @Nullable
    private final byte[] manifestProofBytes;

    public SeiVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configPayload) {
        super(gasCalculator, enhancement, true);
        this.configPayload = requireNonNull(configPayload);
        this.channelId32 = null;
        this.manifestProofBytes = null;
    }

    public SeiVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configPayload,
            @NonNull final byte[] channelId32) {
        super(gasCalculator, enhancement, true);
        this.configPayload = requireNonNull(configPayload);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestProofBytes = null;
    }

    public SeiVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configPayload,
            @NonNull final byte[] channelId32,
            @NonNull final byte[] manifestProofBytes) {
        super(gasCalculator, enhancement, true);
        this.configPayload = requireNonNull(configPayload);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestProofBytes = requireNonNull(manifestProofBytes);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.info("verifyConfig (Sei) ENTER: configPayload={} bytes", configPayload.length);

        final SeiCometBftProofVerifier.VerifiedConfig verified;
        try {
            verified = SeiCometBftProofVerifier.verifyConfigPayload(configPayload);
        } catch (final ProofException e) {
            log.warn("verifyConfig (Sei): payload rejected: {}", e.getMessage());
            return fail();
        } catch (final Exception e) {
            log.warn("verifyConfig (Sei): unexpected error", e);
            return fail();
        }

        final ClprLedgerConfiguration parsed = verified.ledgerConfiguration();
        log.info(
                "verifyConfig (Sei): verifier returned chainId={}, serviceAddress={}, "
                        + "initialTrustAnchor={} bytes, initialTrustAnchorId={}, endpoints={}",
                parsed.chainId(),
                parsed.serviceAddress(),
                parsed.initialTrustAnchor().length(),
                parsed.initialTrustAnchorId(),
                parsed.endpoints().size());
        if (parsed.initialTrustAnchor().length() == 0) {
            log.warn(
                    "verifyConfig (Sei): proven config has empty initial_trust_anchor; rejecting chainId={}, serviceAddress={}",
                    parsed.chainId(),
                    parsed.serviceAddress());
            return fail();
        }

        if (channelId32 == null) {
            return v1Success(parsed);
        }
        if (manifestProofBytes == null) {
            return v2Success(parsed);
        }
        // V3: proven manifest verbatim when the verifier supplied one; otherwise a bring-up seed-fallback
        // (version 1, bound to the proven service address, seeded with the config's endpoints) so the
        // channel bootstraps a dial target. Sei has no config-path manifest-proof producer yet (see
        // SeiCometBftProofVerifier.VerifiedConfig#endpointManifestBytes), so the seed-fallback is the live
        // path; the real manifest advances via the bundle path (Step 1b).
        final byte[] provenManifestBytes = verified.endpointManifestBytes();
        final ClprEndpointManifest manifest;
        if (provenManifestBytes.length > 0) {
            try {
                manifest = ClprEndpointManifest.PROTOBUF.parse(
                        Bytes.wrap(provenManifestBytes).toReadableSequentialData());
            } catch (final Exception e) {
                log.warn("verifyConfig (Sei): proven manifest bytes are not a ClprEndpointManifest", e);
                return fail();
            }
        } else {
            manifest = ClprEndpointManifest.newBuilder()
                    .version(1L)
                    .serviceAddress(parsed.serviceAddress())
                    .endpoints(parsed.endpoints())
                    .build();
        }
        return v3Success(parsed, manifest);
    }

    @NonNull
    private PricedResult v1Success(@NonNull final ClprLedgerConfiguration parsed) {
        final var configBytesOut = ClprLedgerConfiguration.PROTOBUF.toBytes(parsed);
        log.info("verifyConfig (Sei) EXIT: SUCCESS config={} bytes", configBytesOut.length());
        return gasOnly(
                successResult(
                        SeiVerifyConfigTranslator.VERIFY_CONFIG
                                .getOutputs()
                                .encode(Tuple.singleton(configBytesOut.toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult v2Success(@NonNull final ClprLedgerConfiguration parsed) {
        final byte[] serviceAddressBytes = parsed.serviceAddress().toByteArray();
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(channelId32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);

        final ClprThrottles t = parsed.throttlesOrElse(ClprThrottles.DEFAULT);
        final Tuple throttlesTuple = Tuple.of(
                BigInteger.valueOf(t.maxMessagesPerBundle()),
                BigInteger.valueOf(t.maxMessagePayloadBytes()),
                BigInteger.valueOf(t.maxGasPerMessage()),
                BigInteger.valueOf(t.maxQueueDepth()),
                BigInteger.valueOf(t.maxSyncBytes()));

        final Tuple[] endpointTuples = parsed.endpoints().stream()
                .map(ep -> {
                    final ClprServiceEndpoint se = ep.serviceEndpointOrElse(ClprServiceEndpoint.DEFAULT);
                    return Tuple.of(
                            se.ipAddress(),
                            (long) se.port(),
                            ep.tlsCertificate().toByteArray(),
                            ep.accountId().toByteArray());
                })
                .toArray(Tuple[]::new);

        final var ts = parsed.timestamp();
        final long peerConfigNanos = ts != null ? ts.seconds() * 1_000_000_000L + ts.nanos() : 0L;

        log.info(
                "verifyConfig V2 (Sei) EXIT: SUCCESS chainId={} endpoints={}", parsed.chainId(), endpointTuples.length);
        return gasOnly(
                successResult(
                        SeiVerifyConfigTranslator.VERIFY_CONFIG_V2
                                .getOutputs()
                                .encode(Tuple.from(
                                        channelContextBytes,
                                        parsed.chainId(),
                                        serviceAddressBytes,
                                        BigInteger.valueOf(peerConfigNanos),
                                        throttlesTuple,
                                        parsed.initialTrustAnchor().toByteArray(),
                                        parsed.initialTrustAnchorId().toByteArray(),
                                        (Object) endpointTuples)),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult v3Success(
            @NonNull final ClprLedgerConfiguration parsed, @NonNull final ClprEndpointManifest manifest) {
        final byte[] serviceAddressBytes = parsed.serviceAddress().toByteArray();
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(channelId32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);

        // Throttles struct (uint32,uint64,uint64,uint32,uint64,uint32,uint32): Long for uint32,
        // BigInteger for uint64.
        final ClprThrottles t = parsed.throttlesOrElse(ClprThrottles.DEFAULT);
        final Tuple throttlesTuple = Tuple.from(
                Long.valueOf(t.maxMessagesPerBundle()),
                BigInteger.valueOf(t.maxMessagePayloadBytes()),
                BigInteger.valueOf(t.maxGasPerMessage()),
                Long.valueOf(t.maxQueueDepth()),
                BigInteger.valueOf(t.maxSyncBytes()),
                Long.valueOf(t.maxLocalEndpoints()),
                Long.valueOf(t.maxPeerEndpoints()));

        final var ts = parsed.timestamp();
        final long peerConfigNanos = ts != null ? ts.seconds() * 1_000_000_000L + ts.nanos() : 0L;

        log.info(
                "verifyConfig V3 (Sei) EXIT: SUCCESS chainId={} manifestVersion={} manifestEndpoints={}",
                parsed.chainId(),
                manifest.version(),
                manifest.endpoints().size());
        return gasOnly(
                successResult(
                        SeiVerifyConfigTranslator.VERIFY_CONFIG_V3
                                .getOutputs()
                                .encode(Tuple.from(
                                        channelContextBytes,
                                        parsed.chainId(),
                                        serviceAddressBytes,
                                        BigInteger.valueOf(peerConfigNanos),
                                        throttlesTuple,
                                        parsed.initialTrustAnchor().toByteArray(),
                                        parsed.initialTrustAnchorId().toByteArray(),
                                        manifestStructTuple(manifest))),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_VERIFIER_CONFIG_FAILED, GAS_REQUIREMENT), CLPR_VERIFIER_CONFIG_FAILED, false);
    }
}
